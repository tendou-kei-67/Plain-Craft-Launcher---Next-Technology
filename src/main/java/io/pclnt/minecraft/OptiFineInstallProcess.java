package io.pclnt.minecraft;

import java.io.File;

/** Calls the vendor installer with the chosen game directory in a separate JVM. */
public final class OptiFineInstallProcess {
    private OptiFineInstallProcess() {}

    public static void main(String[] args) throws Exception {
        Class.forName("optifine.Installer").getMethod("doInstall", File.class).invoke(null, new File(args[0]));
    }
}
