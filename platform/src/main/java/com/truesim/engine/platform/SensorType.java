package com.truesim.engine.platform;

/** A motion sensor: accelerometers in m/s² and gyroscopes in rad/s, both as x, y, z. */
public enum SensorType {
    UNKNOWN, ACCELEROMETER, GYROSCOPE,
    /** The left Joy-Con's accelerometer. */
    ACCELEROMETER_LEFT,
    GYROSCOPE_LEFT,
    /** The right Joy-Con's accelerometer. */
    ACCELEROMETER_RIGHT,
    GYROSCOPE_RIGHT
}
