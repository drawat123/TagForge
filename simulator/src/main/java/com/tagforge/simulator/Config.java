package com.tagforge.simulator;

public record Config(
        int devices,
        int tags,
        long interval,
        boolean synchronised,
        String broker,
        String server,
        int qos) {

    public static Config parse(String[] args) {
        int devices = 10;
        int tags = 50;
        long interval = 1000;
        boolean synchronised = false;
        String broker = "tcp://localhost:1883";
        String server = "http://localhost:8080";
        int qos = 1;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (("--devices".equals(arg) || "-d".equals(arg)) && i + 1 < args.length) {
                devices = Integer.parseInt(args[++i]);
            } else if (("--tags".equals(arg) || "-t".equals(arg)) && i + 1 < args.length) {
                tags = Integer.parseInt(args[++i]);
            } else if (("--interval".equals(arg) || "-i".equals(arg)) && i + 1 < args.length) {
                interval = Long.parseLong(args[++i]);
            } else if ("--synchronised".equals(arg) || "--synchronized".equals(arg) || "-s".equals(arg)) {
                synchronised = true;
            } else if (("--broker".equals(arg) || "-b".equals(arg)) && i + 1 < args.length) {
                broker = args[++i];
            } else if ("--server".equals(arg) && i + 1 < args.length) {
                server = args[++i];
            } else if (("--qos".equals(arg) || "-q".equals(arg)) && i + 1 < args.length) {
                qos = Integer.parseInt(args[++i]);
            } else if (!arg.startsWith("-")) {
                if (i == 0) {
                    try {
                        devices = Integer.parseInt(arg);
                    } catch (NumberFormatException ignored) {}
                } else if (i == 1) {
                    server = arg;
                }
            }
        }
        return new Config(devices, tags, interval, synchronised, broker, server, qos);
    }
}
