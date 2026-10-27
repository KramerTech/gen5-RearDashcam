package android.content;

import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

// Compile-only stub of the hidden framework interface; the unit's framework.jar provides it.
public interface IIntentReceiver extends IInterface {
    void performReceive(Intent intent, int resultCode, String data, Bundle extras,
            boolean ordered, boolean sticky, int sendingUser) throws RemoteException;

    abstract class Stub extends android.os.Binder implements IIntentReceiver {
        public Stub() {}
        public IBinder asBinder() { return this; }
    }
}
