# OJP Query Result Caching - Production Deployment Guide

## Overview

This guide provides step-by-step instructions for deploying query result caching to production environments. The deployment follows a gradual rollout strategy to minimize risk and validate performance in real-world scenarios.

## Prerequisites

✅ **Before deploying:**
- All 349 tests pass successfully
- Performance benchmarks validated
- Security review completed
- Operations team trained on troubleshooting
- Rollback plan documented

> **⚠️ Multi-Server Environment Note:** The current v0.5.0-beta implementation uses local caching only. Each OJP server instance maintains its own independent cache with no synchronization between servers. In multi-server deployments, write operations invalidate only the local server's cache—other servers' caches remain until TTL expires. **Use shorter TTLs (30-60 seconds) in clustered environments** to limit staleness windows. Distributed cache synchronization is under discussion for future releases.

## Deployment Strategy

### Phase-Based Rollout

| Phase | Target | Duration | Success Criteria |
|-------|--------|----------|------------------|
| **Pilot** | 1 low-risk app | 48-72 hours | Hit rate >60%, zero errors |
| **Limited** | 3-5 apps | 1 week | Hit rate >70%, <0.1% errors |
| **General** | All eligible apps | 2-4 weeks | Hit rate >60%, stable performance |

### Rollback Criteria

**Immediate rollback if:**
- Error rate increases >1%
- Query latency increases >50ms (P95)
- Database load increases >20%
- Memory usage exceeds limits
- Cache corruption detected

## Pilot Deployment

### Step 1: Select Pilot Application

**Ideal pilot application characteristics:**
- ✅ Read-heavy workload (>70% reads)
- ✅ Known query patterns
- ✅ Low business criticality
- ✅ Good existing monitoring
- ✅ Team available for quick response

**Recommended applications:**
- Analytics dashboards
- Reporting systems
- Admin panels
- Internal tools

**Avoid for pilot:**
- ❌ Payment processing
- ❌ User authentication
- ❌ Mission-critical transactional systems
- ❌ Real-time data pipelines

### Step 2: Configure Caching

**Example: Analytics API**

Create `ojp.properties` (or update existing):

```properties
# Enable caching for analytics database
analytics_db.ojp.cache.enabled=true

# Cache report queries (conservative TTL)
analytics_db.ojp.cache.queries.1.pattern=SELECT .* FROM reports WHERE .*
analytics_db.ojp.cache.queries.1.ttl=300s
analytics_db.ojp.cache.queries.1.invalidateOn=reports

# Cache dashboard queries
analytics_db.ojp.cache.queries.2.pattern=SELECT .* FROM dashboards WHERE .*
analytics_db.ojp.cache.queries.2.ttl=600s
analytics_db.ojp.cache.queries.2.invalidateOn=dashboards

# Cache user profile queries (longer TTL)
analytics_db.ojp.cache.queries.3.pattern=SELECT .* FROM users WHERE id = .*
analytics_db.ojp.cache.queries.3.ttl=1800s
analytics_db.ojp.cache.queries.3.invalidateOn=users

# Enable cache metrics (already enabled by default)
ojp.telemetry.cache.metrics.enabled=true
```

**Configuration validation:**
- ✅ Patterns are specific (not too broad like `SELECT .*`)
- ✅ TTL values appropriate for data freshness requirements
- ✅ InvalidateOn tables correct
- ✅ Regex patterns tested and validated

### Step 3: Deploy OJP Server

**Deployment checklist:**

1. **Backup current configuration**
   ```bash
   cp ojp.properties ojp.properties.backup
   ```

2. **Update OJP server binary**
   - Deploy new version with caching support
   - Verify version: Check logs for "Cache metrics enabled" message

3. **Update application configuration**
   - Add cache properties to `ojp.properties`
   - Validate configuration (OJP will log warnings for invalid config)

4. **Restart application**
   - Use rolling restart for zero downtime
   - Monitor logs for cache initialization messages

5. **Verify deployment**
   ```bash
   # Check cache metrics are being exported
   curl http://localhost:9090/metrics | grep ojp.cache
   ```

### Step 4: Monitor Performance

**First 2 hours - Intensive monitoring:**
- ✅ Check error logs every 15 minutes
- ✅ Monitor cache hit rate (target >40%)
- ✅ Watch query latency (should decrease)
- ✅ Verify database load (should decrease)

**First 24 hours - Regular monitoring:**
- ✅ Check metrics every 2 hours
- ✅ Review cache statistics
- ✅ Monitor for any anomalies
- ✅ Validate invalidation is working

**24-72 hours - Validation period:**
- ✅ Analyze hit rate trends
- ✅ Review performance improvements
- ✅ Check for any edge cases
- ✅ Gather user feedback

**Success criteria for pilot:**
- ✅ Hit rate: >60%
- ✅ Error rate: <0.01%
- ✅ Query latency reduction: >30%
- ✅ Database load reduction: >20%
- ✅ Zero cache-related errors

## Limited Rollout

### Step 5: Expand to 3-5 Applications

**After successful pilot (72+ hours):**

1. **Select next applications:**
   - Similar characteristics to pilot
   - Different query patterns (for validation)
   - Gradually increase criticality

2. **Deploy using same process:**
   - Follow steps 2-4 for each application
   - Deploy one at a time (24-48 hour gaps)
   - Monitor each deployment

3. **Monitor aggregate metrics:**
   - Combined hit rate across all apps
   - Overall system performance
   - Database cluster health

**Expand criteria:**
- ✅ Pilot hit rate maintained >60%
- ✅ No issues reported in 72 hours
- ✅ Ops team confident with troubleshooting

## General Rollout

### Step 6: Production-Wide Deployment

**After successful limited rollout (1+ week):**

1. **Create rollout schedule:**
   - Group applications by criticality
   - Start with low-criticality apps
   - End with mission-critical apps

2. **Deploy in waves:**
   - Wave 1: Low criticality (5-10 apps/day)
   - Wave 2: Medium criticality (3-5 apps/day)
   - Wave 3: High criticality (1-2 apps/day)

3. **Monitor continuously:**
   - Automated alerting for issues
   - Daily performance reviews
   - Weekly rollout status meetings

**Completion criteria:**
- ✅ All eligible applications deployed
- ✅ Hit rate goals achieved
- ✅ Performance targets met
- ✅ Zero critical issues

## Monitoring & Alerting

### Key Metrics to Monitor

**Performance Metrics:**
- `ojp.cache.hits` - Cache hit count
- `ojp.cache.misses` - Cache miss count
- `ojp.cache.hit_rate` - Hit rate percentage
- `ojp.query.execution.time` - Query latency
- `ojp.cache.size.bytes` - Memory usage

**Operational Metrics:**
- `ojp.cache.evictions` - Cache evictions (size/TTL)
- `ojp.cache.invalidations` - Write-triggered invalidations
- `ojp.cache.rejections` - Entries rejected (too large)

**Health Metrics:**
- Error rate
- Query success rate
- Database connection pool usage

### Prometheus Queries

**Cache hit rate:**
```promql
rate(ojp_cache_hits_total[5m]) / 
(rate(ojp_cache_hits_total[5m]) + rate(ojp_cache_misses_total[5m]))
```

**Query latency (P95):**
```promql
histogram_quantile(0.95, 
  rate(ojp_query_execution_time_bucket[5m])
)
```

**Cache memory usage:**
```promql
ojp_cache_size_bytes
```

**Invalidation rate:**
```promql
rate(ojp_cache_invalidations_total[5m])
```

### Grafana Dashboard

**Dashboard sections:**
1. **Overview** - Hit rate, query latency, error rate
2. **Performance** - Query time by source (cache vs database)
3. **Operations** - Evictions, invalidations, rejections
4. **Health** - Error rates, memory usage, database load

**Import dashboard:** `grafana-dashboard.json` (see separate file)

### Alerts

**Critical alerts:**
```yaml
- alert: CacheHitRateLow
  expr: ojp_cache_hit_rate < 0.4
  for: 10m
  annotations:
    summary: "Cache hit rate below 40% for {{ $labels.datasource }}"
    
- alert: CacheMemoryHigh
  expr: ojp_cache_size_bytes > 500000000  # 500MB
  for: 5m
  annotations:
    summary: "Cache memory usage exceeds 500MB for {{ $labels.datasource }}"

- alert: QueryLatencyHigh
  expr: histogram_quantile(0.95, ojp_query_execution_time_bucket) > 0.1
  for: 10m
  annotations:
    summary: "P95 query latency exceeds 100ms"
```

## Troubleshooting

### Common Issues

**1. Low hit rate (<40%)**

**Symptoms:**
- Cache hit rate below expectations
- Minimal performance improvement

**Diagnosis:**
```bash
# Check cache statistics
curl http://localhost:9090/metrics | grep ojp.cache

# Review configured patterns
grep "ojp.cache.queries" ojp.properties
```

**Resolution:**
- Review query patterns - may be too specific
- Check TTL values - may be too short
- Verify queries match patterns
- See [CACHE_USER_GUIDE.md](../guides/CACHE_USER_GUIDE.md) for pattern tuning

**2. High memory usage**

**Symptoms:**
- Cache size exceeds limits
- Frequent evictions
- JVM memory pressure

**Diagnosis:**
```bash
# Check cache size per datasource
curl http://localhost:9090/metrics | grep ojp.cache.size

# Review rejection statistics
curl http://localhost:9090/metrics | grep ojp.cache.rejections
```

**Resolution:**
- Reduce TTL values
- Add size limits to configuration
- Exclude large result sets from caching
- Consider per-query size limits

**3. Stale data issues**

**Symptoms:**
- Users see outdated data
- Cache not invalidating on writes

**Diagnosis:**
```bash
# Check invalidation statistics
curl http://localhost:9090/metrics | grep ojp.cache.invalidations

# Review application write patterns
grep "INSERT\|UPDATE\|DELETE" application.log
```

**Resolution:**
- Verify `invalidateOn` table names correct
- Check write queries executing through OJP
- Reduce TTL values if immediate consistency required
- Review SqlTableExtractor compatibility

**4. Performance degradation**

**Symptoms:**
- Query latency increased after caching enabled
- Database load unchanged or increased

**Diagnosis:**
```bash
# Compare query times
curl http://localhost:9090/metrics | grep ojp.query.execution.time

# Check cache overhead
curl http://localhost:9090/metrics | grep ojp.cache.operations
```

**Resolution:**
- Review regex pattern complexity (may be expensive)
- Check for cache lock contention
- Verify sufficient memory available
- Consider disabling caching for specific queries

### Emergency Procedures

**Disable caching immediately:**

**Option 1: Per-datasource (recommended)**
```properties
# Set enabled=false
datasource.ojp.cache.enabled=false
```

**Option 2: Server-wide (nuclear option)**
```bash
# Set environment variable
export OJP_CACHE_ENABLED=false

# Restart application
```

**Option 3: Rollback deployment**
```bash
# Restore previous OJP version
./rollback-ojp.sh

# Verify caching disabled in logs
```

## Operations Runbook

### Daily Operations

**Morning checks:**
- ✅ Review overnight alerts
- ✅ Check hit rate trends
- ✅ Verify no errors in logs
- ✅ Confirm metrics collection working

**Weekly tasks:**
- ✅ Review cache performance metrics
- ✅ Analyze hit rate by datasource
- ✅ Tune TTL values if needed
- ✅ Update documentation with learnings

**Monthly tasks:**
- ✅ Performance review with engineering teams
- ✅ Evaluate candidates for cache expansion
- ✅ Review and optimize cache configurations
- ✅ Update monitoring dashboards

### Maintenance Windows

**Cache warming (optional):**
```bash
# Pre-warm cache with common queries
./warm-cache.sh --datasource analytics_db --queries common-queries.sql
```

**Cache statistics reset:**
```bash
# Clear statistics (not cache contents)
curl -X POST http://localhost:8080/admin/cache/reset-stats
```

**Manual cache invalidation:**
```bash
# Invalidate specific tables
curl -X POST http://localhost:8080/admin/cache/invalidate \
  -d '{"datasource":"analytics_db","tables":["reports","dashboards"]}'
```

## Post-Deployment Review

### Week 1 Review

**Metrics to evaluate:**
- Average hit rate across all datasources
- Query latency improvements
- Database load reduction
- Error rates and incidents
- User-reported issues

**Questions to answer:**
- Did we achieve performance targets?
- Were there any unexpected issues?
- Is hit rate trending upward?
- Are users seeing improved performance?
- What optimizations are needed?

### Month 1 Review

**Performance analysis:**
- Hit rate by datasource
- ROI analysis (latency reduction vs. memory cost)
- Identify best/worst performing applications
- Cache efficiency trends

**Optimization opportunities:**
- Applications not yet using caching
- Query patterns that could benefit
- TTL tuning recommendations
- Configuration improvements

**Lessons learned:**
- Document what worked well
- Record challenges and solutions
- Update troubleshooting guide
- Share best practices with teams

## Success Metrics

**Technical metrics:**
- ✅ Cache hit rate: >60% (target >70%)
- ✅ Query latency reduction: >30%
- ✅ Database load reduction: >20%
- ✅ Error rate: <0.01%
- ✅ Memory usage: <500MB per datasource

**Business metrics:**
- ✅ Page load time improvement
- ✅ User satisfaction scores
- ✅ Infrastructure cost reduction
- ✅ Database capacity freed up

## Next Steps

**After successful deployment:**
1. Monitor continuously for 30 days
2. Tune configurations based on learnings
3. Expand to additional applications
4. Consider advanced features:
   - Query-level cache control
   - Dynamic TTL adjustment
   - Cache preloading strategies
   - Distributed caching (future enhancement)

## Support

**Documentation:**
- User Guide: [CACHE_USER_GUIDE.md](../guides/CACHE_USER_GUIDE.md)
- Troubleshooting: See section above
- API Documentation: `documents/api/cache-api.md`

**Contacts:**
- Cache Implementation Team: cache-team@company.com
- Operations Support: ops-support@company.com
- Emergency Escalation: See incident response playbook

---

**Version:** 1.0  
**Last Updated:** 2026-03-26  
**Status:** Production Ready ✅
