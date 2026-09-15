package com.tagforge.simulator;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Simulator {

    private static final Logger log = LoggerFactory.getLogger(Simulator.class);

    private final DeviceProvisioning deviceProvisioning;

    private final VirtualDevices virtualDevices;

    public Simulator(DeviceProvisioning deviceProvisioning, VirtualDevices virtualDevices) {
        this.deviceProvisioning = deviceProvisioning;
        this.virtualDevices = virtualDevices;
    }

    public void run(int deviceCount) throws Exception {
        List<UUID> deviceIds = deviceProvisioning.createDevices(deviceCount);
        log.info("Provisioned {} device ids", deviceIds.size());
        virtualDevices.startPublish(deviceIds);
    }

    public static void main(String[] args) {
        Config config = Config.parse(args);

        Metrics metrics = new Metrics();

        try (DeviceProvisioning provisioning = new DeviceProvisioning(config.server());
                VirtualDevices virtualDevices = new VirtualDevices(
                        config.broker(), config.tags(), config.interval(),
                        config.synchronised(), config.qos(), metrics)) {

            Runtime.getRuntime().addShutdownHook(new Thread(virtualDevices::close));
            new Simulator(provisioning, virtualDevices).run(config.devices());
        } catch (InterruptedException e) {
            // Restore the flag: swallowing it hides the cancellation from everything above.
            Thread.currentThread().interrupt();
            log.error("Interrupted while running simulator", e);
            System.exit(1);
        } catch (Exception e) {
            // Exit non-zero and say why. Silence is the one thing a load tool must not do.
            log.error("Simulator failed", e);
            System.exit(1);
        }
    }
}
