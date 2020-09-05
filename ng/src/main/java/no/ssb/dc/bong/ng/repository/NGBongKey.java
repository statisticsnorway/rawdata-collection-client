package no.ssb.dc.bong.ng.repository;

import no.ssb.dc.bong.commons.rawdata.RepositoryKey;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class NGBongKey implements RepositoryKey {

    static final AtomicLong seq = new AtomicLong(0);

    final String filename;
    final Long locationNo;
    final Integer bongNo;
    final Long buyTimestamp;
    final Long lineIndex;

    public NGBongKey() {
        this(null, null, null, null);
    }

    public NGBongKey(String filename, Long locationNo, Integer bongNo, Long buyTimestamp) {
        this.filename = filename;
        this.locationNo = locationNo;
        this.bongNo = bongNo;
        this.buyTimestamp = buyTimestamp;
        this.lineIndex = seq.incrementAndGet();
    }

    public NGBongKey(String filename, Long locationNo, Integer bongNo, Long buyTimestamp, Long lineIndex) {
        this.filename = filename;
        this.locationNo = locationNo;
        this.bongNo = bongNo;
        this.buyTimestamp = buyTimestamp;
        this.lineIndex = lineIndex;
    }

    @Override
    public RepositoryKey fromByteBuffer(ByteBuffer keyBuffer) {
        Objects.requireNonNull(keyBuffer);
        int filenameLength = keyBuffer.getInt();
        byte[] filenameBytes = new byte[filenameLength];
        keyBuffer.get(filenameBytes);
        long locationNo = keyBuffer.getLong();
        int bongNo = keyBuffer.getInt();
        long buyTimestamp = keyBuffer.getLong();
        long index = keyBuffer.getLong();
        return new NGBongKey(new String(filenameBytes, StandardCharsets.UTF_8), locationNo, bongNo, buyTimestamp, index);
    }

    @Override
    public ByteBuffer toByteBuffer(ByteBuffer allocatedBuffer) {
        Objects.requireNonNull(allocatedBuffer);
        byte[] filenameBytes = filename.getBytes(StandardCharsets.UTF_8);
        allocatedBuffer.putInt(filenameBytes.length);
        allocatedBuffer.put(filenameBytes);
        allocatedBuffer.putLong(locationNo);
        allocatedBuffer.putInt(bongNo);
        allocatedBuffer.putLong(buyTimestamp);
        allocatedBuffer.putLong(lineIndex);
        return allocatedBuffer.flip();
    }

    @Override
    public String toPosition() {
        return String.format("%s.%s", locationNo, bongNo);
    }

    public boolean isPartOfBong(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NGBongKey NGBongKey = (NGBongKey) o;
        return Objects.equals(locationNo, NGBongKey.locationNo) &&
                Objects.equals(bongNo, NGBongKey.bongNo);
    }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NGBongKey NGBongKey = (NGBongKey) o;
        return Objects.equals(locationNo, NGBongKey.locationNo) &&
                Objects.equals(bongNo, NGBongKey.bongNo) &&
                Objects.equals(buyTimestamp, NGBongKey.buyTimestamp) &&
                Objects.equals(lineIndex, NGBongKey.lineIndex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(locationNo, bongNo, buyTimestamp, lineIndex);
    }

    @Override
    public String toString() {
        return "BongKey{" +
                "locationNo=" + locationNo +
                ", bongNo=" + bongNo +
                ", buyTimestamp=" + buyTimestamp +
                ", index=" + lineIndex +
                '}';
    }
}
