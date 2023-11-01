package org.altbeacon.beaconreference

import android.app.*
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import org.altbeacon.beacon.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class BeaconReferenceApplication : Application() {
    // the region definition is a wildcard that matches all beacons regardless of identifiers.
    // if you only want to detect beacons with a specific UUID, change the id1 paremeter to
    // a UUID like Identifier.parse("2F234454-CF6D-4A0F-ADF2-F4911BA9FFA6")
//    var region = Region("all-beacons", null, null, null)
//    val region = Region(
//        /* uniqueId = */ "all-beacons",
//        /* id1 = */ Identifier.parse("00b08e64-0a0e-48c7-9571-6008519139ed"),
//        /* id2 = */ null,
//        /* id3 = */ null
//    )

    data class BeaconState(
        var detectedTime: Long = 0L,
        val notificationId: Int = -1
    )

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

//        private val beaconsMap = mutableMapOf<Identifier, BeaconState>()
    private val beaconsMap = ConcurrentHashMap<Identifier, BeaconState>()

    override fun onCreate() {
        super.onCreate()

        val beaconManager = BeaconManager.getInstanceForApplication(this)
        BeaconManager.setDebug(true)

        // By default the AndroidBeaconLibrary will only find AltBeacons.  If you wish to make it
        // find a different type of beacon, you must specify the byte layout for that beacon's
        // advertisement with a line like below.  The example shows how to find a beacon with the
        // same byte layout as AltBeacon but with a beaconTypeCode of 0xaabb.  To find the proper
        // layout expression for other beacon types, do a web search for "setBeaconLayout"
        // including the quotes.
        //
        //beaconManager.getBeaconParsers().clear();
        //beaconManager.getBeaconParsers().add(new BeaconParser().
        //        setBeaconLayout("m:0-1=4c00,i:2-24v,p:24-24"));


        // By default the AndroidBeaconLibrary will only find AltBeacons.  If you wish to make it
        // find a different type of beacon like Eddystone or iBeacon, you must specify the byte layout
        // for that beacon's advertisement with a line like below.
        //
        // If you don't care about AltBeacon, you can clear it from the defaults:
        //beaconManager.getBeaconParsers().clear()

        // Uncomment if you want to block the library from updating its distance model database
        //BeaconManager.setDistanceModelUpdateUrl("")

        // The example shows how to find iBeacon.
        val parser = BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24")
        parser.setHardwareAssistManufacturerCodes(arrayOf(0x004c).toIntArray())
        beaconManager.beaconParsers.add(parser)

        // enabling debugging will send lots of verbose debug information from the library to Logcat
        // this is useful for troubleshooting problmes
        // BeaconManager.setDebug(true)


        // The BluetoothMedic code here, if included, will watch for problems with the bluetooth
        // stack and optionally:
        // - power cycle bluetooth to recover on bluetooth problems
        // - periodically do a proactive scan or transmission to verify the bluetooth stack is OK
        // BluetoothMedic.getInstance().legacyEnablePowerCycleOnFailures(this) // Android 4-12 only
        // BluetoothMedic.getInstance().enablePeriodicTests(this, BluetoothMedic.SCAN_TEST + BluetoothMedic.TRANSMIT_TEST)

        setupBeaconScanning()
    }

    fun setupBeaconScanning() {
        val beaconManager = BeaconManager.getInstanceForApplication(this)
        beaconManager.backgroundBetweenScanPeriod = 5 * 1000L
//        beaconManager.backgroundScanPeriod = 1000
//        beaconManager.backgroundBetweenScanPeriod = 100L

        // By default, the library will scan in the background every 5 minutes on Android 4-7,
        // which will be limited to scan jobs scheduled every ~15 minutes on Android 8+
        // If you want more frequent scanning (requires a foreground service on Android 8+),
        // configure that here.
        // If you want to continuously range beacons in the background more often than every 15 mintues,
        // you can use the library's built-in foreground service to unlock this behavior on Android
        // 8+.   the method below shows how you set that up.
        try {
            setupForegroundService()
        } catch (e: SecurityException) {
            // On Android TIRAMUSU + this security exception will happen
            // if location permission has not been granted when we start
            // a foreground service.  In this case, wait to set this up
            // until after that permission is granted
            Log.d(
                TAG,
                "Not setting up foreground service scanning until location permission granted by user"
            )
            return
        }
        //beaconManager.setEnableScheduledScanJobs(false);
        //beaconManager.setBackgroundBetweenScanPeriod(0);
        //beaconManager.setBackgroundScanPeriod(1100);

        // Ranging callbacks will drop out if no beacons are detected
        // Monitoring callbacks will be delayed by up to 25 minutes on region exit
        // beaconManager.setIntentScanningStrategyEnabled(true)

        beaconsMap.clear()

        // The code below will start "monitoring" for beacons matching the region definition at the top of this file
        beaconManager.startMonitoring(region)
        beaconManager.startRangingBeacons(region)
        // These two lines set up a Live Data observer so this Activity can get beacon data from the Application class
        val regionViewModel =
            BeaconManager.getInstanceForApplication(this).getRegionViewModel(region)

        // observer will be called each time the monitored regionState changes (inside vs. outside region)
        regionViewModel.regionState.observeForever(centralMonitoringObserver)
        // observer will be called each time a new list of beacons is ranged (typically ~1 second in the foreground)
        regionViewModel.rangedBeacons.observeForever(centralRangingObserver)

    }

    private fun setupForegroundService() {
        val broadcastIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, NotificationActionReceiver::class.java).apply {
                action = NOTIFICATION_ACTION_STRING
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationAction = NotificationCompat.Action.Builder(
            R.drawable.ic_launcher_background,
            "종료",
            broadcastIntent
        ).build()

        val builder = NotificationCompat.Builder(this, NOTIFICATION_FG_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setContentTitle("Scanning for Beacons")
            .setContentText("hello~")
            .setOngoing(true)
            .addAction(notificationAction)

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT + PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingIntent)

        notificationManager.createNotificationChannelGroup(
            NotificationChannelGroup(
                "my-group",
                "group name"
            )
        )
        val channelPerNoti = NotificationChannel(
            /* id = */ NOTIFICATION_CHANNEL_ID,
            /* name = */ "My Notification Name",
            /* importance = */ NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "My Notification Channel Description"
            group = "my-group"
        }
        notificationManager.createNotificationChannel(channelPerNoti)

        val channelForeground = NotificationChannel(
            /* id = */ NOTIFICATION_FG_CHANNEL_ID,
            /* name = */ "Foreground Service Notification",
            /* importance = */ NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "My Notification Channel Description"
        }
        notificationManager.createNotificationChannel(channelForeground)

        Log.d(TAG, "Calling enableForegroundServiceScanning")
        BeaconManager.getInstanceForApplication(this)
            .enableForegroundServiceScanning(builder.build(), 456)
        Log.d(TAG, "Back from  enableForegroundServiceScanning")
    }

    private val centralMonitoringObserver = Observer<Int> { state ->
        if (state == MonitorNotifier.OUTSIDE) {
            Log.d("beacons", "[beacons] outside beacon region: $region")
//            sendNotification(Random.nextInt(), "outside beacon region")
        } else {
            Log.d("beacons", "[beacons] inside beacon region: $region")
//            sendNotification(Random.nextInt(), "inside beacon region")
        }
    }

    private val centralRangingObserver = Observer<Collection<Beacon>> { beacons ->
        // beacons: 현재 위치에서 감지된 비콘의 정보 리스트
        // beaconsMap: 감지된 비콘 정보 Map
        Log.d("beacons", "[beacons] centralRangingObserver start, beacons count: $beacons, beaconsMap: ${beaconsMap.size}")
        for (beacon: Beacon in beacons) {
            if (beaconsMap[beacon.id3] == null) { // 새로 들어온 놈인 경우 (일단 minor로만 비교)
                val notificationId = Random.nextInt()
                beaconsMap[beacon.id3] = BeaconState(
                    detectedTime = System.currentTimeMillis(),
                    notificationId = notificationId
                )
                sendNotification(id = notificationId, content = "inside: ${beacon.id3}")
            } else { // 비콘맵에 이미 해당 minor가 있는 경우 감지 시간을 계속 업데이트
                beaconsMap[beacon.id3]?.detectedTime = System.currentTimeMillis()
            }
        }

        Log.d("beacons", "[beacons] beaconsMap: $beaconsMap")
        for (beacon in beaconsMap) {
            // 현재 위치에서 읽힌 비콘(beacons) 리스트에서 map에 저장된 minor가 있는지 확인
            // 없다면 (filteredResult == null) 이므로 현재 감지되진 않았지만 과거에 감지된 적이 있다는 뜻이 됨
            // 따라서 현재 시간과 마지막으로 감지됐던 시간을 뺸 결과가 10초를 넘어갈 경우 해당 비콘이 없다고 판단해 map에서 지움
            // 해당 기능은 비콘이 잠깐 끊긴 경우에는 알람을 받지 않도록 하기 위함임
            val filteredResult = beacons.filter { it.id3 == beacon.key }.getOrNull(0)

            val diffMillis = System.currentTimeMillis() - beacon.value.detectedTime
            Log.d("beacons", "[beacons] timeMillis: $diffMillis")
            if (filteredResult == null && diffMillis >= 10_000) {
                beaconsMap.remove(beacon.key)
//                val notificationId = Random.nextInt()
//                sendNotification(id = notificationId, content = "outside: ${beacon.key}")
                notificationManager.cancel(beacon.value.notificationId)
            }
        }

//        val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.getDefault())
//        beacons.forEach { beacon ->
//            val date = sdf.format(beacon.lastCycleDetectionTimestamp)
//            Log.d("beacons", "[beacons] beacon.lastCycleDetectionTimestamp: $date")
//        }

        val rangeAgeMillis =
            System.currentTimeMillis() - (beacons.firstOrNull()?.lastCycleDetectionTimestamp ?: 0)
        if (rangeAgeMillis < 10000) {
            Log.d(MainActivity.TAG, "Ranged: ${beacons.count()} beacons")

            for (beacon: Beacon in beacons) {
                Log.d(TAG, "$beacon about ${beacon.distance} meters away")
            }
        } else {
//            Log.d(MainActivity.TAG, "Ignoring stale ranged beacons from $rangeAgeMillis millis ago")
            Log.d(
                "beacons",
                "[beacons] Ignoring stale ranged beacons from $rangeAgeMillis millis ago"
            )
        }
    }

    private fun sendNotification(id: Int, content: String) {
        val stackBuilder = TaskStackBuilder.create(this)
        stackBuilder.addNextIntent(Intent(this, MainActivity::class.java))
        val resultPendingIntent = stackBuilder.getPendingIntent(
            0,
            PendingIntent.FLAG_UPDATE_CURRENT + PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Beacon Reference Application")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setContentIntent(resultPendingIntent)
            .setGroup("my-group")
            .setAutoCancel(true)
            .build()

        val summaryNotification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setGroup("my-group")
            .setAutoCancel(true)
            .setGroupSummary(true)
            .build()

        Log.d("beacons", "[beacons] send notification, content: $content")
        notificationManager.notify(id, notification)
        notificationManager.notify(1234, summaryNotification)
    }

    companion object {
        const val TAG = "BeaconReference"
        val region = Region(
            /* uniqueId = */ "all-beacons",
//            /* id1 = */ Identifier.parse("00b08e64-0a0e-48c7-9571-6008519139ed"), // 원본
            /* id1 = */ Identifier.parse("00b08e64-0a0e-48c7-9571-6008519139ee"),
            /* id2 = */ null,
            /* id3 = */ null
        )
    }
}