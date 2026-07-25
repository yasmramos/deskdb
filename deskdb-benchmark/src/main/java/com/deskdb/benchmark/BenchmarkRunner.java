package com.deskdb.benchmark;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Runner class to execute all DeskDB benchmarks.
 * This class provides a convenient way to run all benchmarks without using command line arguments.
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException {
        System.out.println("Starting DeskDB Benchmarks...");
        System.out.println("=============================");

        Options opt = new OptionsBuilder()
                .include(".*Benchmark.*")  // Include all benchmark classes
                .forks(1)                  // Single fork for CI environments
                .warmupIterations(3)       // Warmup iterations
                .warmupTime(TimeValue.seconds(2))
                .measurementIterations(3)  // Measurement iterations
                .measurementTime(TimeValue.seconds(3))
                .shouldFailOnError(true)   // Fail on error for CI
                .shouldDoGC(true)          // Run GC between iterations
                .build();

        new Runner(opt).run();

        System.out.println("=============================");
        System.out.println("Benchmarks completed successfully!");
    }
}
