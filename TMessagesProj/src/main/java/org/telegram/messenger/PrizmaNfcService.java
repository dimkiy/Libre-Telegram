package org.telegram.messenger;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;

public class PrizmaNfcService extends HostApduService {
    public static final byte[] AID = {(byte) 0xF0, 0x4C, 0x49, 0x42, 0x52, 0x45};
    public static final String PREFIX = "LIBRE1:";

    private static final byte[] SW_OK = {(byte) 0x90, 0x00};
    private static final byte[] SW_NOT_FOUND = {(byte) 0x6A, (byte) 0x82};
    private static final byte[] SW_UNKNOWN = {(byte) 0x6D, 0x00};

    private static final long GRACE_MS = 2000;

    public interface Listener {
        void onCodeSent();
    }

    private static volatile byte[] payload;
    private static volatile long payloadUntil;
    private static volatile boolean sent;
    private static volatile Listener listener;

    public static void arm(String token, long untilElapsed, Listener l) {
        payload = (PREFIX + token).getBytes(StandardCharsets.UTF_8);
        payloadUntil = untilElapsed;
        sent = false;
        listener = l;
    }

    public static void disarm() {
        payload = null;
        payloadUntil = 0;
        listener = null;
    }

    public static byte[] selectApdu() {
        byte[] cmd = new byte[6 + AID.length];
        cmd[0] = 0x00;
        cmd[1] = (byte) 0xA4;
        cmd[2] = 0x04;
        cmd[3] = 0x00;
        cmd[4] = (byte) AID.length;
        System.arraycopy(AID, 0, cmd, 5, AID.length);
        cmd[cmd.length - 1] = 0x00;
        return cmd;
    }

    private static boolean isSelectOurAid(byte[] cmd) {
        if (cmd == null || cmd.length < 5 + AID.length) return false;
        if (cmd[0] != 0x00 || cmd[1] != (byte) 0xA4 || cmd[2] != 0x04 || cmd[4] != AID.length) return false;
        for (int i = 0; i < AID.length; i++) {
            if (cmd[5 + i] != AID[i]) return false;
        }
        return true;
    }

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        if (!isSelectOurAid(commandApdu)) {
            return SW_UNKNOWN;
        }
        final byte[] p = payload;
        final long now = SystemClock.elapsedRealtime();
        if (p == null || now > payloadUntil) {
            return SW_NOT_FOUND;
        }
        if (!sent) {
            sent = true;
            payloadUntil = Math.min(payloadUntil, now + GRACE_MS);
            final Listener l = listener;
            if (l != null) {
                AndroidUtilities.runOnUIThread(l::onCodeSent);
            }
        }
        byte[] out = new byte[p.length + 2];
        System.arraycopy(p, 0, out, 0, p.length);
        out[p.length] = SW_OK[0];
        out[p.length + 1] = SW_OK[1];
        return out;
    }

    @Override
    public void onDeactivated(int reason) {
    }
}
