/*
 * Copyright 2015 - 2019 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.DeviceSession;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.BitUtil;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

public class BceProtocolDecoder extends BaseProtocolDecoder {

    public BceProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private static final int DATA_TYPE = 7;

    public static final int MSG_ASYNC_STACK = 0xA5;
    public static final int MSG_STACK_COFIRM = 0x19;
    public static final int MSG_TIME_TRIGGERED = 0xA0;
    public static final int MSG_OUTPUT_CONTROL = 0x41;
    public static final int MSG_OUTPUT_CONTROL_ACK = 0xC1;

    private void decodeMask1(ByteBuf buf, int mask, Position position) {

        if (BitUtil.check(mask, 0)) {
            position.setValid(true);
            position.setLongitude(buf.readFloatLE());
            position.setLatitude(buf.readFloatLE());
            position.setSpeed(UnitsConverter.knotsFromKph(buf.readUnsignedByte()));

            int status = buf.readUnsignedByte();
            position.set(Position.KEY_SATELLITES, BitUtil.to(status, 4));
            position.set(Position.KEY_HDOP, BitUtil.from(status, 4));

            position.setCourse(buf.readUnsignedByte() * 2);
            position.setAltitude(buf.readUnsignedShortLE());

            position.set(Position.KEY_ODOMETER, buf.readUnsignedIntLE());
        }

        if (BitUtil.check(mask, 1)) {
            position.set(Position.KEY_INPUT, buf.readUnsignedShortLE());
        }

        for (int i = 1; i <= 8; i++) {
            if (BitUtil.check(mask, i + 1)) {
                position.set(Position.PREFIX_ADC + i, buf.readUnsignedShortLE());
            }
        }

        if (BitUtil.check(mask, 10)) {
            buf.skipBytes(4);
        }
        if (BitUtil.check(mask, 11)) {
            buf.skipBytes(4);
        }
        if (BitUtil.check(mask, 12)) {
            buf.skipBytes(2);
        }
        if (BitUtil.check(mask, 13)) {
            buf.skipBytes(2);
        }

        if (BitUtil.check(mask, 14)) {
            position.setNetwork(new Network(CellTower.from(
                    buf.readUnsignedShortLE(), buf.readUnsignedByte(),
                    buf.readUnsignedShortLE(), buf.readUnsignedShortLE(),
                    buf.readUnsignedByte())));
            buf.readUnsignedByte();
        }
    }

    private void decodeMask2(ByteBuf buf, int mask, Position position) {

        if (BitUtil.check(mask, 0)) {
            buf.readUnsignedShortLE(); // wheel speed
        }
        if (BitUtil.check(mask, 1)) {
            buf.readUnsignedByte(); // acceleration pedal
        }
        if (BitUtil.check(mask, 2)) {
            buf.readUnsignedIntLE(); // total fuel used
        }
        if (BitUtil.check(mask, 3)) {
            buf.readUnsignedByte(); // fuel level
        }
        if (BitUtil.check(mask, 4)) {
            buf.readUnsignedShortLE(); // engine speed
        }
        if (BitUtil.check(mask, 5)) {
            buf.readUnsignedIntLE(); // total hours
        }
        if (BitUtil.check(mask, 6)) {
            buf.readUnsignedIntLE(); // total distance
        }
        if (BitUtil.check(mask, 7)) {
            buf.readUnsignedByte(); // engine coolant
        }
        if (BitUtil.check(mask, 8)) {
            buf.readUnsignedByte(); // fuel level 2
        }
        if (BitUtil.check(mask, 9)) {
            buf.readUnsignedByte(); // engine load
        }
        if (BitUtil.check(mask, 10)) {
            buf.readUnsignedShortLE(); // service distance
        }
        if (BitUtil.check(mask, 11)) {
            buf.skipBytes(8); // sensors
        }
        if (BitUtil.check(mask, 12)) {
            buf.readUnsignedShortLE(); // ambient air temperature
        }
        if (BitUtil.check(mask, 13)) {
            buf.skipBytes(8); // trailer id
        }
        if (BitUtil.check(mask, 14)) {
            buf.readUnsignedShortLE(); // fuel rate
        }
    }

    private void decodeMask3(ByteBuf buf, int mask, Position position) {

        if (BitUtil.check(mask, 0)) {
            buf.readUnsignedShortLE(); // fuel economy
        }
        if (BitUtil.check(mask, 1)) {
            buf.readUnsignedIntLE(); // fuel consumption
        }
        if (BitUtil.check(mask, 2)) {
            buf.readUnsignedMediumLE(); // axle weight
        }
        if (BitUtil.check(mask, 3)) {
            buf.readUnsignedByte(); // mil status
        }
        if (BitUtil.check(mask, 4)) {
            buf.skipBytes(20); // dtc
        }
        if (BitUtil.check(mask, 5)) {
            buf.readUnsignedShortLE();
        }
        if (BitUtil.check(mask, 6)) {
            position.set(Position.KEY_DRIVER_UNIQUE_ID, String.valueOf(buf.readLongLE()));
        }
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;

        String imei = String.format("%015d", buf.readLongLE());
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            return null;
        }

        List<Position> positions = new LinkedList<>();

        while (buf.readableBytes() > 1) {

            int dataEnd = buf.readUnsignedShortLE() + buf.readerIndex();
            int type = buf.readUnsignedByte();

            if (type != MSG_ASYNC_STACK && type != MSG_TIME_TRIGGERED) {
                return null;
            }

            int confirmKey = buf.readUnsignedByte() & 0x7F;

            while (buf.readerIndex() < dataEnd) {

                Position position = new Position(getProtocolName());
                position.setDeviceId(deviceSession.getDeviceId());

                int structEnd = buf.readUnsignedByte() + buf.readerIndex();

                long time = buf.readUnsignedIntLE();
                if ((time & 0x0f) == DATA_TYPE) {

                    time = time >> 4 << 1;
                    time += 0x47798280; // 01/01/2008
                    position.setTime(new Date(time * 1000));

                    // Read masks
                    int mask;
                    List<Integer> masks = new LinkedList<>();
                    do {
                        mask = buf.readUnsignedShortLE();
                        masks.add(mask);
                    } while (BitUtil.check(mask, 15));

                    mask = masks.get(0);
                    decodeMask1(buf, mask, position);

                    if (masks.size() >= 2) {
                        mask = masks.get(1);
                        decodeMask2(buf, mask, position);
                    }

                    if (masks.size() >= 3) {
                        mask = masks.get(2);
                        decodeMask3(buf, mask, position);
                    }
                }

                buf.readerIndex(structEnd);

                if (position.getValid()) {
                    positions.add(position);
                } else if (!position.getAttributes().isEmpty()) {
                    getLastLocation(position, null);
                    positions.add(position);
                }
            }

            // Send response
            if (type == MSG_ASYNC_STACK && channel != null) {
                ByteBuf response = Unpooled.buffer(8 + 2 + 2 + 1);
                response.writeLongLE(Long.parseLong(imei));
                response.writeShortLE(2);
                response.writeByte(MSG_STACK_COFIRM);
                response.writeByte(confirmKey);

                int checksum = 0;
                for (int i = 0; i < response.writerIndex(); i++) {
                    checksum += response.getUnsignedByte(i);
                }
                response.writeByte(checksum);

                channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
            }
        }

        return positions;
    }

}
