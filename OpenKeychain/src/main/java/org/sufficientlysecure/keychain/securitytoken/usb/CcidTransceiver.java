/*
 * Copyright (C) 2016 Nikita Mikhailov <nikita.s.mikhailov@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.securitytoken.usb;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.auto.value.AutoValue;
import org.bouncycastle.util.Arrays;
import org.sufficientlysecure.keychain.Constants;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class CcidTransceiver {
    private static final int CCID_HEADER_LENGTH = 10;

    private static final int MESSAGE_TYPE_RDR_TO_PC_DATA_BLOCK = 0x80;
    private static final int MESSAGE_TYPE_PC_TO_RDR_ICC_POWER_ON = 0x62;
    private static final int MESSAGE_TYPE_PC_TO_RDR_XFR_BLOCK = 0x6f;

    private static final int COMMAND_STATUS_SUCCESS = 0;
    private static final int COMMAND_STATUS_TIME_EXTENSION_RQUESTED = 2;

    private static final int SLOT_NUMBER = 0x00;

    private static final int ICC_STATUS_SUCCESS = 0;

    private static final int TIMEOUT = 20 * 1000; // 20s

    private final UsbDeviceConnection usbConnection;
    private final UsbEndpoint usbBulkIn;
    private final UsbEndpoint usbBulkOut;

    private byte currentSequenceNumber;

    CcidTransceiver(UsbDeviceConnection connection, UsbEndpoint bulkIn, UsbEndpoint bulkOut) {
        usbConnection = connection;
        usbBulkIn = bulkIn;
        usbBulkOut = bulkOut;
    }

    /**
     * Power of ICC
     * Spec: 6.1.1 PC_to_RDR_IccPowerOn
     */
    @NonNull
    public CcidDataBlock iccPowerOn() throws UsbTransportException {
        byte sequenceNumber = currentSequenceNumber++;
        final byte[] iccPowerCommand = {
                MESSAGE_TYPE_PC_TO_RDR_ICC_POWER_ON,
                0x00, 0x00, 0x00, 0x00,
                SLOT_NUMBER,
                sequenceNumber,
                0x00, // voltage select = auto
                0x00, 0x00 // reserved for future use
        };

        sendRaw(iccPowerCommand, 0, iccPowerCommand.length);

        long startTime = System.currentTimeMillis();
        while (true) {
            try {
                return receiveDataBlock(sequenceNumber);
            } catch (Exception e) {
                Log.e(Constants.TAG, "Error waiting for device power on", e);
                // Try more startTime
                if (System.currentTimeMillis() - startTime > TIMEOUT) {
                    break;
                }
            }
            SystemClock.sleep(100);
        }

        throw new UsbTransportException("Couldn't power up Security Token");
    }

    /**
     * Transmits XfrBlock
     * 6.1.4 PC_to_RDR_XfrBlock
     *
     * @param payload payload to transmit
     */
    public CcidDataBlock sendXfrBlock(byte[] payload) throws UsbTransportException {
        int l = payload.length;
        byte sequenceNumber = currentSequenceNumber++;
        byte[] headerData = {
                MESSAGE_TYPE_PC_TO_RDR_XFR_BLOCK,
                (byte) l, (byte) (l >> 8), (byte) (l >> 16), (byte) (l >> 24),
                SLOT_NUMBER,
                sequenceNumber,
                0x00, // block waiting time
                0x00, 0x00 // level parameters
        };
        byte[] data = Arrays.concatenate(headerData, payload);

        int sentBytes = 0;
        while (sentBytes < data.length) {
            int bytesToSend = Math.min(usbBulkIn.getMaxPacketSize(), data.length - sentBytes);
            sendRaw(data, sentBytes, bytesToSend);
            sentBytes += bytesToSend;
        }

        return receiveDataBlock(sequenceNumber);
    }

    private CcidDataBlock receiveDataBlock(byte expectedSequenceNumber) throws UsbTransportException {
        CcidDataBlock response;
        do {
            response = receiveDataBlockImmediate(expectedSequenceNumber);
        } while (response.isStatusTimeoutExtensionRequest());

        if (!response.isStatusSuccess()) {
            throw new UsbTransportException("USB-CCID error: " + response);
        }

        return response;
    }

    private CcidDataBlock receiveDataBlockImmediate(byte expectedSequenceNumber) throws UsbTransportException {
        byte[] buffer = new byte[usbBulkIn.getMaxPacketSize()];

        int readBytes = usbConnection.bulkTransfer(usbBulkIn, buffer, buffer.length, TIMEOUT);
        if (readBytes < CCID_HEADER_LENGTH) {
            throw new UsbTransportException("USB-CCID error - failed to receive CCID header");
        }
        if (buffer[0] != (byte) MESSAGE_TYPE_RDR_TO_PC_DATA_BLOCK) {
            throw new UsbTransportException("USB-CCID error - bad CCID header type " + buffer[0]);
        }

        CcidDataBlock result = CcidDataBlock.parseHeaderFromBytes(buffer);

        if (expectedSequenceNumber != result.getSeq()) {
            throw new UsbTransportException("USB-CCID error - expected sequence number " +
                    expectedSequenceNumber + ", got " + result);
        }

        byte[] dataBuffer = new byte[result.getDataLength()];
        int bufferedBytes = readBytes - CCID_HEADER_LENGTH;
        System.arraycopy(buffer, CCID_HEADER_LENGTH, dataBuffer, 0, bufferedBytes);

        while (bufferedBytes < dataBuffer.length) {
            readBytes = usbConnection.bulkTransfer(usbBulkIn, buffer, buffer.length, TIMEOUT);
            if (readBytes < 0) {
                throw new UsbTransportException("USB error - failed reading response data! Header: " + result);
            }
            System.arraycopy(buffer, 0, dataBuffer, bufferedBytes, readBytes);
            bufferedBytes += readBytes;
        }

        result = result.withData(dataBuffer);
        return result;
    }

    private void sendRaw(byte[] data, int offset, int length) throws UsbTransportException {
        int tr1;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
            tr1 = usbConnection.bulkTransfer(usbBulkOut, data, offset, length, TIMEOUT);
        } else {
            byte[] dataToSend = Arrays.copyOfRange(data, offset, offset+length);
            tr1 = usbConnection.bulkTransfer(usbBulkOut, dataToSend, dataToSend.length, TIMEOUT);
        }

        if (tr1 != length) {
            throw new UsbTransportException("USB error - failed to transmit data (" + tr1 + "/" + length + ")");
        }
    }

    /** Corresponds to 6.2.1 RDR_to_PC_DataBlock. */
    @AutoValue
    public abstract static class CcidDataBlock {
        public abstract int getDataLength();
        public abstract byte getSlot();
        public abstract byte getSeq();
        public abstract byte getStatus();
        public abstract byte getError();
        public abstract byte getChainParameter();
        @Nullable
        public abstract byte[] getData();

        static CcidDataBlock parseHeaderFromBytes(byte[] headerBytes) {
            ByteBuffer buf = ByteBuffer.wrap(headerBytes);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            byte type = buf.get();
            if (type != (byte) MESSAGE_TYPE_RDR_TO_PC_DATA_BLOCK) {
                throw new IllegalArgumentException("Header has incorrect type value!");
            }
            int dwLength = buf.getInt();
            byte bSlot = buf.get();
            byte bSeq = buf.get();
            byte bStatus = buf.get();
            byte bError = buf.get();
            byte bChainParameter = buf.get();

            return new AutoValue_CcidTransceiver_CcidDataBlock(
                    dwLength, bSlot, bSeq, bStatus, bError, bChainParameter, null);
        }

        CcidDataBlock withData(byte[] data) {
            if (getData() != null) {
                throw new IllegalStateException("Cannot add data to this class twice!");
            }

            return new AutoValue_CcidTransceiver_CcidDataBlock(
                    getDataLength(), getSlot(), getSeq(), getStatus(), getError(), getChainParameter(), data);
        }

        byte getIccStatus() {
            return (byte) (getStatus() & 0x03);
        }

        byte getCommandStatus() {
            return (byte) ((getStatus() >> 6) & 0x03);
        }

        boolean isStatusTimeoutExtensionRequest() {
            return getCommandStatus() == COMMAND_STATUS_TIME_EXTENSION_RQUESTED;
        }

        boolean isStatusSuccess() {
            return getIccStatus() == ICC_STATUS_SUCCESS && getCommandStatus() == COMMAND_STATUS_SUCCESS;
        }
    }
}
