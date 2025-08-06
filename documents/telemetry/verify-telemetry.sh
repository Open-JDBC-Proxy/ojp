#!/bin/bash
# OJP Telemetry Verification Script
# This script helps verify that your OJP telemetry setup is working correctly

set -e

# Configuration
PROMETHEUS_PORT=${OJP_PROMETHEUS_PORT:-9090}
PROMETHEUS_HOST=${PROMETHEUS_HOST:-localhost}
TIMEOUT=${TIMEOUT:-10}

echo "=========================================="
echo "OJP Telemetry Verification Script"
echo "=========================================="
echo "Checking telemetry setup for OJP server..."
echo ""

# Check if OJP server is responding on gRPC port
echo "1. Checking if OJP server is running..."
if command -v grpcurl &> /dev/null; then
    echo "   Using grpcurl to check gRPC health..."
    if timeout $TIMEOUT grpcurl -plaintext ${PROMETHEUS_HOST}:1059 grpc.health.v1.Health/Check 2>/dev/null; then
        echo "   ✅ OJP gRPC server is responding"
    else
        echo "   ⚠️  OJP gRPC server not responding (this is expected if not running)"
    fi
else
    echo "   ℹ️  grpcurl not available, skipping gRPC health check"
    echo "   Install grpcurl with: brew install grpcurl (Mac) or download from GitHub"
fi

echo ""

# Check Prometheus metrics endpoint
echo "2. Checking Prometheus metrics endpoint..."
METRICS_URL="http://${PROMETHEUS_HOST}:${PROMETHEUS_PORT}/metrics"
echo "   Checking: $METRICS_URL"

if command -v curl &> /dev/null; then
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout $TIMEOUT "$METRICS_URL" || echo "000")
    
    if [ "$HTTP_STATUS" = "200" ]; then
        echo "   ✅ Prometheus metrics endpoint is accessible"
        
        # Check for OpenTelemetry metrics
        METRICS_CONTENT=$(curl -s --connect-timeout $TIMEOUT "$METRICS_URL" 2>/dev/null || echo "")
        
        if echo "$METRICS_CONTENT" | grep -q "grpc_server"; then
            echo "   ✅ gRPC server metrics are available"
        else
            echo "   ⚠️  gRPC server metrics not found (may appear after first requests)"
        fi
        
        if echo "$METRICS_CONTENT" | grep -q "otel_scope_info"; then
            echo "   ✅ OpenTelemetry scope information found"
        else
            echo "   ℹ️  OpenTelemetry scope info not found (normal for basic setup)"
        fi
        
        # Count available metrics
        METRIC_COUNT=$(echo "$METRICS_CONTENT" | grep -c "^# HELP" || echo "0")
        echo "   📊 Available metrics families: $METRIC_COUNT"
        
    elif [ "$HTTP_STATUS" = "000" ]; then
        echo "   ❌ Cannot connect to metrics endpoint"
        echo "      Make sure OJP server is running and port $PROMETHEUS_PORT is accessible"
    else
        echo "   ❌ Metrics endpoint returned HTTP $HTTP_STATUS"
    fi
else
    echo "   ❌ curl not available - cannot check metrics endpoint"
fi

echo ""

# Check configuration
echo "3. Checking configuration..."
echo "   Prometheus port: $PROMETHEUS_PORT"
echo "   Prometheus host: $PROMETHEUS_HOST"

if [ -n "$OJP_OPENTELEMETRY_ENABLED" ]; then
    echo "   OpenTelemetry enabled: $OJP_OPENTELEMETRY_ENABLED"
else
    echo "   OpenTelemetry enabled: true (default)"
fi

if [ -n "$OJP_OPENTELEMETRY_ENDPOINT" ]; then
    echo "   ⚠️  OTLP endpoint configured: $OJP_OPENTELEMETRY_ENDPOINT"
    echo "      Note: Distributed tracing is not yet fully implemented"
else
    echo "   OTLP endpoint: not configured (tracing not available)"
fi

echo ""

# Recommendations
echo "4. Recommendations..."
echo "   📋 To integrate with Prometheus:"
echo "      Add this to your prometheus.yml:"
echo "      - job_name: 'ojp-server'"
echo "        static_configs:"
echo "          - targets: ['${PROMETHEUS_HOST}:${PROMETHEUS_PORT}']"
echo ""
echo "   📋 To test manually:"
echo "      curl $METRICS_URL"
echo ""
echo "   📋 For production:"
echo "      - Set OJP_PROMETHEUS_ALLOWED_IPS to restrict access"
echo "      - Monitor gRPC error rates and response times"
echo "      - Set up alerting for connection failures"

echo ""
echo "=========================================="
echo "Verification complete!"
echo "=========================================="