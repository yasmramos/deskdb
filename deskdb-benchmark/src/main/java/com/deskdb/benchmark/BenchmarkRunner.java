package com.deskdb.benchmark;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Runner class to execute all DeskDB benchmarks.
 * This class provides a convenient way to run all benchmarks without using command line arguments.
 * Optimized for CI environments with reduced iterations.
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException {
        System.out.println("Starting DeskDB Benchmarks...");
        System.out.println("=============================");

        Options opt = new OptionsBuilder()
                .include(".*Benchmark.*")  // Include all benchmark classes
                .forks(1)                  // Single fork for CI environments
                .warmupIterations(2)       // Reduced warmup for CI
                .warmupTime(TimeValue.seconds(1))
                .measurementIterations(2)  // Reduced measurements for CI
                .measurementTime(TimeValue.seconds(2))
                .shouldFailOnError(true)   // Fail on error for CI
                .shouldDoGC(true)          // Run GC between iterations
                .build();

        new Runner(opt).run();

        System.out.println("=============================");
        System.out.println("Benchmarks completed successfully!");
    }
}
