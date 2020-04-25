package info.nightscout.androidaps.plugins.pump.common.hw.rileylink.service;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import javax.inject.Inject;

import dagger.android.DaggerService;
import info.nightscout.androidaps.logging.AAPSLogger;
import info.nightscout.androidaps.logging.LTag;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.RileyLinkCommunicationManager;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.RileyLinkConst;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.RileyLinkUtil;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.ble.RFSpy;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.ble.RileyLinkBLE;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.ble.defs.RileyLinkEncodingType;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.defs.RileyLinkError;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.defs.RileyLinkServiceState;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.defs.RileyLinkTargetDevice;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.service.data.ServiceResult;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.service.data.ServiceTransport;
import info.nightscout.androidaps.plugins.pump.medtronic.defs.PumpDeviceState;
import info.nightscout.androidaps.plugins.pump.medtronic.util.MedtronicUtil;
import info.nightscout.androidaps.utils.sharedPreferences.SP;

import static info.nightscout.androidaps.plugins.pump.common.hw.rileylink.RileyLinkUtil.getRileyLinkCommunicationManager;

/**
 * Created by andy on 5/6/18.
 * Split from original file and renamed.
 */
public abstract class RileyLinkService extends DaggerService {

    @Inject protected AAPSLogger aapsLogger;
    @Inject protected SP sp;
    @Inject protected Context context;


    public RileyLinkBLE rileyLinkBLE; // android-bluetooth management
    protected BluetoothAdapter bluetoothAdapter;
    protected RFSpy rfspy; // interface for RL xxx Mhz radio.
    protected RileyLinkBroadcastReceiver mBroadcastReceiver;
    protected RileyLinkServiceData rileyLinkServiceData;
    protected RileyLinkBluetoothStateReceiver bluetoothStateReceiver;

    @Override
    public void onCreate() {
        super.onCreate();

        RileyLinkUtil.setContext(this.context);
        RileyLinkUtil.setRileyLinkService(this);
        RileyLinkUtil.setEncoding(getEncoding());
        initRileyLinkServiceData();

        mBroadcastReceiver = new RileyLinkBroadcastReceiver(this, this.context);
        mBroadcastReceiver.registerBroadcasts();


        bluetoothStateReceiver = new RileyLinkBluetoothStateReceiver();
        bluetoothStateReceiver.registerBroadcasts(this);
    }

    /**
     * Get Encoding for RileyLink communication
     */
    public abstract RileyLinkEncodingType getEncoding();


    /**
     * If you have customized RileyLinkServiceData you need to override this
     */
    public abstract void initRileyLinkServiceData();


    @Override
    public boolean onUnbind(Intent intent) {
        //aapsLogger.warn(LTag.PUMPCOMM, "onUnbind");
        return super.onUnbind(intent);
    }


    @Override
    public void onRebind(Intent intent) {
        //aapsLogger.warn(LTag.PUMPCOMM, "onRebind");
        super.onRebind(intent);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        //LOG.error("I die! I die!");

        if (rileyLinkBLE != null) {
            rileyLinkBLE.disconnect(); // dispose of Gatt (disconnect and close)
            rileyLinkBLE = null;
        }

        if (mBroadcastReceiver != null) {
            mBroadcastReceiver.unregisterBroadcasts();
        }

        if (bluetoothStateReceiver != null) {
            bluetoothStateReceiver.unregisterBroadcasts(this);
        }

    }


    /**
     * Prefix for Device specific broadcast identifier prefix (for example MSG_PUMP_ for pump or
     * MSG_POD_ for Omnipod)
     *
     * @return
     */
    public abstract String getDeviceSpecificBroadcastsIdentifierPrefix();


    public abstract boolean handleDeviceSpecificBroadcasts(Intent intent);


    public abstract void registerDeviceSpecificBroadcasts(IntentFilter intentFilter);


    public abstract RileyLinkCommunicationManager getDeviceCommunicationManager();


    // Here is where the wake-lock begins:
    // We've received a service startCommand, we grab the lock.
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        RileyLinkUtil.setContext(getApplicationContext());
        return (START_STICKY);
    }


    public boolean bluetoothInit() {
        aapsLogger.debug(LTag.PUMPCOMM, "bluetoothInit: attempting to get an adapter");
        RileyLinkUtil.setServiceState(RileyLinkServiceState.BluetoothInitializing);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            aapsLogger.error("Unable to obtain a BluetoothAdapter.");
            RileyLinkUtil.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.NoBluetoothAdapter);
        } else {

            if (!bluetoothAdapter.isEnabled()) {
                aapsLogger.error("Bluetooth is not enabled.");
                RileyLinkUtil.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.BluetoothDisabled);
            } else {
                RileyLinkUtil.setServiceState(RileyLinkServiceState.BluetoothReady);
                return true;
            }
        }

        return false;
    }


    // returns true if our Rileylink configuration changed
    public boolean reconfigureRileyLink(String deviceAddress) {

        if (rileyLinkBLE == null) {
            RileyLinkUtil.setServiceState(RileyLinkServiceState.BluetoothInitializing);
            return false;
        }

        RileyLinkUtil.setServiceState(RileyLinkServiceState.RileyLinkInitializing);

        if (rileyLinkBLE.isConnected()) {
            if (deviceAddress.equals(rileyLinkServiceData.rileylinkAddress)) {
                aapsLogger.info(LTag.PUMPCOMM, "No change to RL address.  Not reconnecting.");
                return false;
            } else {
                aapsLogger.warn(LTag.PUMPCOMM, "Disconnecting from old RL (" + rileyLinkServiceData.rileylinkAddress
                        + "), reconnecting to new: " + deviceAddress);

                rileyLinkBLE.disconnect();
                // prolly need to shut down listening thread too?
                // SP.putString(MedtronicConst.Prefs.RileyLinkAddress, deviceAddress);

                rileyLinkServiceData.rileylinkAddress = deviceAddress;
                rileyLinkBLE.findRileyLink(rileyLinkServiceData.rileylinkAddress);
                return true;
            }
        } else {
            aapsLogger.debug(LTag.PUMPCOMM, "Using RL " + deviceAddress);

            if (RileyLinkUtil.getServiceState() == RileyLinkServiceState.NotStarted) {
                if (!bluetoothInit()) {
                    aapsLogger.error("RileyLink can't get activated, Bluetooth is not functioning correctly. {}",
                            RileyLinkUtil.getError() != null ? RileyLinkUtil.getError().name() : "Unknown error (null)");
                    return false;
                }
            }

            rileyLinkBLE.findRileyLink(deviceAddress);

            return true;
        }
    }


    public void sendServiceTransportResponse(ServiceTransport transport, ServiceResult serviceResult) {
    }


    // FIXME: This needs to be run in a session so that is interruptable, has a separate thread, etc.
    public void doTuneUpDevice() {

        RileyLinkUtil.setServiceState(RileyLinkServiceState.TuneUpDevice);
        MedtronicUtil.setPumpDeviceState(PumpDeviceState.Sleeping);

        double lastGoodFrequency = 0.0d;

        if (rileyLinkServiceData.lastGoodFrequency == null) {
            lastGoodFrequency = sp.getDouble(RileyLinkConst.Prefs.LastGoodDeviceFrequency, 0.0d);
        } else {
            lastGoodFrequency = rileyLinkServiceData.lastGoodFrequency;
        }

        double newFrequency;

        newFrequency = getDeviceCommunicationManager().tuneForDevice();

        if ((newFrequency != 0.0) && (newFrequency != lastGoodFrequency)) {
            aapsLogger.info(LTag.PUMPCOMM, "Saving new pump frequency of {} MHz", newFrequency);
            sp.putDouble(RileyLinkConst.Prefs.LastGoodDeviceFrequency, newFrequency);
            rileyLinkServiceData.lastGoodFrequency = newFrequency;
            rileyLinkServiceData.tuneUpDone = true;
            rileyLinkServiceData.lastTuneUpTime = System.currentTimeMillis();
        }

        if (newFrequency == 0.0d) {
            // error tuning pump, pump not present ??
            RileyLinkUtil
                    .setServiceState(RileyLinkServiceState.PumpConnectorError, RileyLinkError.TuneUpOfDeviceFailed);
        } else {
            getRileyLinkCommunicationManager().clearNotConnectedCount();
            RileyLinkUtil.setServiceState(RileyLinkServiceState.PumpConnectorReady);
        }
    }


    public void disconnectRileyLink() {

        if (this.rileyLinkBLE != null && this.rileyLinkBLE.isConnected()) {
            this.rileyLinkBLE.disconnect();
            rileyLinkServiceData.rileylinkAddress = null;
        }

        RileyLinkUtil.setServiceState(RileyLinkServiceState.BluetoothReady);
    }


    /**
     * Get Target Device for Service
     */
    public RileyLinkTargetDevice getRileyLinkTargetDevice() {
        return this.rileyLinkServiceData.targetDevice;
    }


    public void changeRileyLinkEncoding(RileyLinkEncodingType encodingType) {
        if (rfspy != null) {
            rfspy.setRileyLinkEncoding(encodingType);
        }
    }
}
