package dev.skramer.dashcam;

/** For the install hook (scripts/preinstall.sh): prints the HAL's camera type, or -1 if it can't say. */
public final class CameraCheck {
    private CameraCheck() {}

    public static void main(String[] args) {
        System.out.println(RearCam.type());
    }
}
