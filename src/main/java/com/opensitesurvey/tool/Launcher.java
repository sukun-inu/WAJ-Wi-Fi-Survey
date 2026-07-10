package com.waj.tool;

/**
 * Plain (non-Application) entry point used by the fat jar / jpackage image.
 * java -jar refuses to start a Main-Class that directly extends
 * javafx.application.Application when JavaFX is on the classpath instead of
 * the module path, so this indirection is required for the packaged exe.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        if (HeadlessRunner.tryRun(args)) {
            return;
        }
        App.main(args);
    }
}
