package com.android.bluetooth.btservice;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import android.bluetooth.IBluetoothVSCallback;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

class VSCallbackList extends RemoteCallbackList<IBluetoothVSCallback> {

    private static final String TAG = "BluetoothVSCallbackList";

    private static final boolean DBG = false;

    private static final class CallbackCookie {
        /**
         * Used to avoid a race condition where an initial update is posted on
         * a handler, but could be out of date when it is actually run.
         */
        public boolean updated = false;
        /**
         * Keeps track of the event filter for this specific callback.
         */
        public byte[] filterMask;
        public byte[] filterValue;
        /**
         * Keeps track of whether the client has been present in a non-pending state.
         * This is to avoid a case where a client shows up during a disable and
         * would see the off state as an indication of failure to enable.
         */
        public boolean hasSeenStableState = false;
    }

    private final AdapterState mAdapterState;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /**
     * Keep manual track of cookies so that we can modify each binder's filter.
     */
    private final Map<IBinder, CallbackCookie> mCookieMap =
            new HashMap<IBinder, CallbackCookie>();

    private int mPreviousState = AdapterState.STATE_OFF;

    public VSCallbackList(AdapterState adapterState) {
        mAdapterState = adapterState;
    }

    @Override
    public void onCallbackDied(IBluetoothVSCallback callback) {
        super.onCallbackDied(callback);
        checkState();
    }

    @Override
    public boolean register(final IBluetoothVSCallback callback) {
        final CallbackCookie cookie = new CallbackCookie();
        if(!super.register(callback, cookie)) {
            return false;
        }

        synchronized(mCookieMap) {
            mCookieMap.put(callback.asBinder(), cookie);
        }

        synchronized (this) {
            switch(mPreviousState)
            {
                case AdapterState.STATE_OFF:
                    // checkState should send a POWER_ON which would change state to
                    // pending, so treat it the same.
                    // FALL-THROUGH
                case AdapterState.STATE_PENDING:
                    // Wait for a transition to off, on, or powered.
                    break;
                case AdapterState.STATE_ON:
                case AdapterState.STATE_POWERED:
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                synchronized (cookie) {
                                    if(!cookie.updated) {
                                        callback.onInterfaceReady();
                                    }
                                }
                            } catch (RemoteException e) {
                                // Ignore and wait for onCallbackDied
                            }
                        }
                    });
                    break;
            }
        }

        checkState();
        return true;
    }

    @Override
    public boolean unregister(IBluetoothVSCallback callback) {
        boolean result = super.unregister(callback);
        checkState();
        synchronized (mCookieMap) {
            mCookieMap.remove(callback.asBinder());
        }
        return result;
    }

    public void setFilter(IBluetoothVSCallback callback, byte[] mask, byte[] value) {
        synchronized(mCookieMap) {
            // First make sure the filter is completely valid.
            if (mask == null || value == null) {
                mask = value = null;
            } else if (mask.length > value.length) {
                mask = Arrays.copyOfRange(mask, 0, value.length);
            }
            for(int i = 0; i < mask.length; i++) {
                value[i] = (byte)(value[i] & mask[i]);
            }
            CallbackCookie cookie = mCookieMap.get(callback.asBinder());
            if (cookie == null) return;
            synchronized (cookie) {
                cookie.filterMask = mask;
                cookie.filterValue = value;
            }
        }
    }

    public synchronized boolean areLocksHeld()
    {
        return getRegisteredCallbackCount() > 0;
    }

    private synchronized void checkState()
    {
        if(areLocksHeld()) {
            mAdapterState.sendMessage(AdapterState.POWER_ON);
        } else {
            mAdapterState.sendMessage(AdapterState.POWER_OFF);
        }
    }

    public synchronized void onStateUpdate(int newState)
    {
        if (DBG) Log.d(TAG, "State update: " + newState + ", oldstate=" + mPreviousState);
        if(newState == mPreviousState) return;
        Boolean stateToSend = null;
        switch(newState)
        {
            case AdapterState.STATE_OFF:
                stateToSend = false;
                break;
            case AdapterState.STATE_POWERED:
            case AdapterState.STATE_ON:
                stateToSend = true;
                break;
            case AdapterState.STATE_PENDING:
            default:
                // Don't send a state update.
                break;
        }

        if (DBG) Log.d(TAG, "State to send: " + stateToSend);
        if(stateToSend != null) {
            int numBroadcasts = beginBroadcast();
            if (DBG) Log.d(TAG, "Broadcasting state to " + numBroadcasts + " receivers.");
            for(int i = 0; i < numBroadcasts; i++) {
                try {
                    IBluetoothVSCallback cb = getBroadcastItem(i);
                    CallbackCookie cookie = (CallbackCookie) getBroadcastCookie(i);
                    synchronized (cookie) {
                        cookie.updated = true;
                        if (stateToSend) {
                            cb.onInterfaceReady();
                        } else if(cookie.hasSeenStableState) {
                            cb.onInterfaceDown();
                            unregister(cb);
                        }
                        cookie.hasSeenStableState = true;
                    }
                } catch (RemoteException e) {
                    // Ignore, as we should get an a call to onCallbackDied to
                    // take care of this.
                }
            }
            finishBroadcast();
        }

        mPreviousState = newState;
    }

    public synchronized void onVSCommandComplete(short opcode, byte[] params) {
        int numBroadcasts = beginBroadcast();
        for(int i = 0; i < numBroadcasts; i++) {
            try {
                IBluetoothVSCallback cb = getBroadcastItem(i);
                cb.vendorSpecificCommandCompleteReceived(opcode, params);
            } catch (RemoteException e) {
                // Ignore, as we should get an a call to onCallbackDied to
                // take care of this.
            }
        }
        finishBroadcast();
    }

    public synchronized void onVSEvent(byte[] params) {
        int numBroadcasts = beginBroadcast();
        if (DBG) Log.d(TAG, String.format("Sending out VS event to %d potential clients.", numBroadcasts));
        for (int i = 0; i < numBroadcasts; i++) {
            try {
                CallbackCookie cookie = (CallbackCookie) getBroadcastCookie(i);
                synchronized(cookie) {
                    // If no filter specified, all events are filtered.
                    if (cookie.filterMask == null) continue;
                    // If filter is longer than payload we automatically filter.
                    if (params.length < cookie.filterMask.length) continue;
                    boolean filtered = false;
                    for (int j = 0; j < cookie.filterMask.length; j++) {
                        if (((byte)(params[j] & cookie.filterMask[j])) !=
                                cookie.filterValue[j]) {
                            filtered = true; break;
                        }
                    }
                    if (filtered) continue;
                }

                IBluetoothVSCallback cb = getBroadcastItem(i);
                cb.vendorSpecificEventReceived(params);
            } catch (RemoteException e) {
                // Ignore and wait for onCallbackDied
            }
        }
        finishBroadcast();
    }
}
