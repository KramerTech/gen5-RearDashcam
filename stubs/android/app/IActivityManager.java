package android.app;

import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

// Compile-only stub: just the methods this project calls (KitKat signatures).
public interface IActivityManager {
    Intent registerReceiver(IApplicationThread caller, String callerPackage, IIntentReceiver receiver,
            IntentFilter filter, String requiredPermission, int userId) throws RemoteException;
    void finishReceiver(IBinder who, int resultCode, String resultData, Bundle map, boolean abortBroadcast)
            throws RemoteException;
}
