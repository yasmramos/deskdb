package com.deskdb.benchmark;

import com.deskdb.core.storage.compression.RLECompressor;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark comparativo de compresión RLE vs datos sin comprimir.
 * Mide throughput y ratio de compresión para diferentes patrones de datos.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(2)
public class CompressionBenchmark {

    private RLECompressor compressor;
    private byte[] repetitiveData;
    private byte[] randomData;
    private byte[] mixedData;

    @Setup
    public void setup() {
        compressor = new RLECompressor();
        
        // Datos altamente repetitivos (mejor caso para RLE)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("AAAAABBBBBCCCCC");
        }
        repetitiveData = sb.toString().getBytes(StandardCharsets.UTF_8);
        
        // Datos aleatorios (peor caso para RLE)
        randomData = new byte[15000];
        for (int i = 0; i < randomData.length; i++) {
            randomData[i] = (byte) (i % 256);
        }
        
        // Datos mixtos (caso realista)
        StringBuilder mixedSb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            mixedSb.append("AAAABBBB").append((char)('A' + (i % 26)));
        }
        mixedData = mixedSb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Benchmark
    public void compressRepetitiveData(Blackhole bh) {
        byte[] compressed = compressor.compress(repetitiveData);
        bh.consume(compressed);
    }

    @Benchmark
    public void decompressRepetitiveData(Blackhole bh) {
        byte[] compressed = compressor.compress(repetitiveData);
        byte[] decompressed = compressor.decompress(compressed);
        bh.consume(decompressed);
    }

    @Benchmark
    public void compressRandomData(Blackhole bh) {
        byte[] compressed = compressor.compress(randomData);
        bh.consume(compressed);
    }

    @Benchmark
    public void decompressRandomData(Blackhole bh) {
        byte[] compressed = compressor.compress(randomData);
        byte[] decompressed = compressor.decompress(compressed);
        bh.consume(decompressed);
    }

    @Benchmark
    public void compressMixedData(Blackhole bh) {
        byte[] compressed = compressor.compress(mixedData);
        bh.consume(compressed);
    }

    @Benchmark
    public void decompressMixedData(Blackhole bh) {
        byte[] compressed = compressor.compress(mixedData);
        byte[] decompressed = compressor.decompress(compressed);
        bh.consume(decompressed);
    }

    @Benchmark
    public void roundTripRepetitive(Blackhole bh) {
        byte[] original = repetitiveData;
        byte[] compressed = compressor.compress(original);
        byte[] decompressed = compressor.decompress(compressed);
        bh.consume(decompressed);
    }

    @Benchmark
    public void roundTripMixed(Blackhole bh) {
        byte[] original = mixedData;
        byte[] compressed = compressor.compress(original);
        byte[] decompressed = compressor.decompress(compressed);
        bh.consume(decompressed);
    }
}
