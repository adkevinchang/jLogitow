package com.trychen.logitow;

import com.trychen.logitow.jni.NativeUtils;
import com.trychen.logitow.jni.SystemType;
import com.trychen.logitow.jni.SystemVersion;
import com.trychen.logitow.stack.BLEStackCallback;
import com.trychen.logitow.stack.BlockData;
import com.trychen.logitow.stack.BluetoothState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * core class
 *
 * @author trychen
 * @since 1.0
 */
public final class LogiTowBLEStack {
    private static Logger logger = LogManager.getLogger("Logitow BLE Stack");

    private static boolean available = false;

    public static boolean isAvailable() {
        return available;
    }

    static {
        // start loading library
        if (SystemVersion.isCurrentSystemSupport()) {
            try {
                System.loadLibrary("logitow");
                setup();
                available = true;
            } catch (Throwable error) {
                try {
                    NativeUtils.loadLibraryFromJar("/logitow." + SystemType.getCurrentSystem().getJniLibSuffix());
                    setup();
                    available = true;
                } catch (UnsatisfiedLinkError|IOException err) {
                    // fount it unable to found usable native lib for current system
                    System.err.println("Couldn't find a satisfied native logitow lib for current system");
                    err.printStackTrace();
                }
            }
        }
    }

    private static boolean isScanning = false, isConnected = false;

    private static ExecutorService executorService = Executors.newSingleThreadExecutor();

    /**
     * let native env get the jclass instance of LogiTowBLEStack.class
     *
     * Class:     com_trychen_logitow_LogiTowBLEStack
     * Method:    setup
     * Signature: ()V
     */
    private static native void setup();

    /**
     * the device's unique uuid.
     * warning: it is not unique in different PC or MAC
     */
    private static Set<UUID> connectedDevicesUUID = new HashSet<>();

    /**
     * callback while received something or ready to do something
     */
    private static final List<BLEStackCallback> callbacks = new LinkedList<>();

    public static boolean addCallback(BLEStackCallback callback) {
        return callbacks.add(callback);
    }

    public static boolean removeCallback(BLEStackCallback callback) {
        return callbacks.remove(callback);
    }

    /**
     * start scanning logitow if scan haven't started.
     *
     * @return start scanning success
     */
    public static boolean startScan() {
        if (isScanning) return true;

        for (BLEStackCallback callback : callbacks)
            if (callback.onStartScan()) return false;

        isScanning = true;
        return startScanDevice();
    }

    /**
     * get the bluetooth state,
     *
     * @return the state code {@link }
     */
    public static BluetoothState getBluetoothState() {
        return BluetoothState.valueOf(getNativeBluetoothState());
    }

    /**
     * get the native raw id (BluetoothState)
     * for mac: <code>typedef NS_ENUM(NSInteger, CBCentralManagerState) {
     * CBCentralManagerStateUnknown = 0,
     * CBCentralManagerStateResetting,
     * CBCentralManagerStateUnsupported,
     * CBCentralManagerStateUnauthorized,
     * CBCentralManagerStatePoweredOff,
     * CBCentralManagerStatePoweredOn,
     * };</code>
     */
    public static native int getNativeBluetoothState();

    /**
     * start scanning device
     */
    private static native boolean startScanDevice();

    /**
     * stop scanning devices
     */
    private static void stopScan() {
        if (isScanning) stopScanDevice();
    }

    /**
     * stop scanning logitow
     */
    private static native void stopScanDevice();

    /**
     * disconnect device
     *
     * @param scanForOtherDevice restart scanning
     */
    public static native void disconnect(boolean scanForOtherDevice);

    /**
     * This method is use for jni to notify that has disconnected to a logitow device.
     *
     * You can refer to the following to call this method in jni.
     * <pre>
     *      <code>
     *          jboolean rescan = true; // rescan is param "isScanning"
     *          jmethodID notify_disconnected_funid = env->GetStaticMethodID(jni_ble_class,"notifyDisconnected","(Ljava/lang/String;Z)V");
     *          env->CallStaticVoidMethod(env, jni_ble_class, notify_disconnected_funid, uuid, rescan);
     *      </code>
     * </pre>
     *
     * @param isScanning true if has started scanning
     */
    private static void notifyDisconnected(String uuid, boolean isScanning) {
        LogiTowBLEStack.isScanning = isScanning;
        isConnected = false;

        UUID deviceUUID = UUID.fromString(uuid);

        connectedDevicesUUID.remove(deviceUUID);

        executorService.submit(() -> callbacks.forEach(it -> it.onDisconnected(deviceUUID)));
    }

    /**
     * this method is use for jni to notify that has connected to a logitow device
     *
     * You can refer to the following to call this method in jni.
     * <pre>
     *      <code>
     *          jmethodID notify_connected_funid = env->GetStaticMethodID(jni_ble_class,"notifyConnected","(Ljava/lang/String;)V");
     *          env->CallStaticVoidMethod(jni_ble_class, notify_connected_funid, uuid);
     *      </code>
     * </pre>
     */
    private static void notifyConnected(String uuid) {
        isConnected = true;
        isScanning = false;


        UUID deviceUUID = UUID.fromString(uuid);

        connectedDevicesUUID.add(deviceUUID);

        // submit to the blocked
        executorService.submit(() -> callbacks.forEach(it -> it.onConnected(deviceUUID)));
    }

    /**
     * if stack has been connected to a logitow device
     */
    public static boolean isConnected() {
        return isConnected;
    }

    /**
     * previous received data, using to ignore the verification data
     */
    private static byte[] previousData;

    /**
     * this method is use for jni to send the data received from logitow device
     */
    private static void notifyBlockData(String uuid, byte[] data) {
        // ignore the verification data
        if (previousData != null && Arrays.equals(data, previousData)) {
            previousData = null;
            return;
        }
        previousData = data;

        // submit to single-thread executor to avoid thread being blocked and handle data in order
        executorService.submit(() -> {
            UUID deviceUUID = UUID.fromString(uuid);

            int insertBlockID = data[2] & 0xFF |
                    (data[1] & 0xFF) << 8 |
                    (data[0] & 0xFF) << 16;

            int insertFace = data[3] & 0xFF;

            int newBlockID = data[6] & 0xFF |
                    (data[5] & 0xFF) << 8 |
                    (data[4] & 0xFF) << 16;

            BlockData blockData = new BlockData(insertBlockID, insertFace, newBlockID);

            // output formatted data
            logger.info(blockData.toString());

            // notify to the consumers
            executorService.submit(() -> {
                for (BLEStackCallback callback : callbacks)
                    if (callback.onBlockDataReceived(deviceUUID, blockData)) return;
            });
        });
    }

    private static CompletableFuture<Float> voltageFutureCache;

    /**
     * The range of the voltage is from 2.1V to 1.5V.
     * when the voltage is close to 1.5V, it means that ForgeMod may no battery
     */
    public static CompletableFuture<Float> getVoltage(){
        if (voltageFutureCache == null) {
            voltageFutureCache = new CompletableFuture<>();
            writeToGetVoltage();
        }
        return voltageFutureCache;
    }

    public static native void writeToGetVoltage();

    public static float getMinVoltage() { return 1.5f; }
    public static float getMaxVoltage() { return 2.1f; }

    /**
     * get the percent of rest battery
     */
    public static CompletableFuture<Float> getRestBattery() {
        throw new UnsupportedOperationException("Getting batter not implemented");
//        return getVoltage().thenApply((voltage) -> (voltage - getMinVoltage()) / (getMaxVoltage() - getMinVoltage()));
    }

    /**
     * this method is for jni to notify changing the voltage for future
     */
    public static void notifyVoltage(String uuid, float voltage) {
        if (voltageFutureCache != null) voltageFutureCache.complete(voltage);

        UUID deviceUUID = UUID.fromString(uuid);

        executorService.submit(() -> {
            for (BLEStackCallback callback : callbacks)
                if (callback.onVoltageDataReceived(deviceUUID, voltage)) return;
        });
    }
}
