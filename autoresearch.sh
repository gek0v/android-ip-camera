#!/usr/bin/env bash
set -e

# Run the 1080p MJPEG benchmark suite
OUTPUT=$(./gradlew testDebugUnitTest --tests "*Mjpeg1080pBenchmarkTest*" --rerun-tasks -i 2>&1)

FPS=$(echo "$OUTPUT" | grep -o 'BENCHMARK_RESULT fps=[0-9.]*' | cut -d'=' -f2 | head -n1)
LATENCY=$(echo "$OUTPUT" | grep -o 'latency_ms=[0-9.]*' | cut -d'=' -f2 | head -n1)

if [ -z "$FPS" ]; then
    echo "ERROR: Could not find BENCHMARK_RESULT in test output:"
    echo "$OUTPUT" | tail -n 40
    exit 1
fi

echo "METRIC fps=$FPS"
echo "METRIC latency_ms=$LATENCY"
exit 0
