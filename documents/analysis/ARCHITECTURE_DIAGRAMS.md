# Dynamic OJP Server Discovery - Visual Architecture

## Current Architecture (Static Configuration)

```
┌─────────────────────────────────────────────────────┐
│           Application (JDBC Client)                 │
│                                                     │
│  Connection URL:                                    │
│  jdbc:ojp[server1:1059,server2:1059,server3:1059]_ │
│      postgresql://localhost:5432/mydb               │
│                                                     │
│  ❌ Static - requires restart to change             │
└─────────────────────────────────────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │   Static Server List   │
        │  [Hard-coded in URL]   │
        └───────────────────────┘
                    │
      ┌─────────────┼─────────────┐
      ▼             ▼             ▼
┌─────────┐   ┌─────────┐   ┌─────────┐
│  OJP    │   │  OJP    │   │  OJP    │
│ Server1 │   │ Server2 │   │ Server3 │
│ :1059   │   │ :1059   │   │ :1059   │
└─────────┘   └─────────┘   └─────────┘
      │             │             │
      └─────────────┼─────────────┘
                    ▼
            ┌──────────────┐
            │   Database   │
            │  PostgreSQL  │
            └──────────────┘
```

## Proposed Architecture (Dynamic Discovery)

### Option 1: DNS-Based Discovery

```
┌─────────────────────────────────────────────────────┐
│           Application (JDBC Client)                 │
│                                                     │
│  Connection URL:                                    │
│  jdbc:ojp[discovery:dns:ojp-cluster.example.com]_  │
│      postgresql://localhost:5432/mydb               │
│                                                     │
│  ✅ Dynamic - no restart needed                     │
└─────────────────────────────────────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │  DNS Service Discovery │
        │   (SRV Records)        │
        │  Refresh: 30s          │
        └───────────────────────┘
                    │
                    ▼ (Query _ojp._tcp.ojp-cluster.example.com)
        ┌───────────────────────┐
        │    DNS Server         │
        │                       │
        │  SRV Records:         │
        │  → server1:1059       │
        │  → server2:1059       │
        │  → server3:1059       │
        │  → server4:1059 (NEW!)│
        └───────────────────────┘
                    │
      ┌─────────────┼─────────────┬─────────────┐
      ▼             ▼             ▼             ▼
┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐
│  OJP    │   │  OJP    │   │  OJP    │   │  OJP    │
│ Server1 │   │ Server2 │   │ Server3 │   │ Server4 │
│ :1059   │   │ :1059   │   │ :1059   │   │ :1059   │
│ ✅ Active│   │ ✅ Active│   │ ✅ Active│   │ ✅ NEW!  │
└─────────┘   └─────────┘   └─────────┘   └─────────┘
      │             │             │             │
      └─────────────┼─────────────┼─────────────┘
                    ▼
            ┌──────────────┐
            │   Database   │
            │  PostgreSQL  │
            └──────────────┘

Benefits:
✅ Automatic discovery of new servers
✅ No application restart required
✅ Low operational overhead
✅ Works with existing DNS infrastructure
```

### Option 2: Consul Service Discovery

```
┌─────────────────────────────────────────────────────┐
│           Application (JDBC Client)                 │
│                                                     │
│  Connection URL:                                    │
│  jdbc:ojp[discovery:consul:ojp-server]_             │
│      postgresql://localhost:5432/mydb               │
│                                                     │
│  Properties:                                        │
│  ojp.discovery.consul.host=consul.example.com       │
│  ojp.discovery.refresh.interval=10                  │
└─────────────────────────────────────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │ Consul Service        │
        │ Discovery             │
        │                       │
        │ Features:             │
        │ • Health checks       │
        │ • Watch API (real-time│
        │ • Service metadata    │
        └───────────────────────┘
                    │
                    ▼ (Query healthy instances)
        ┌───────────────────────┐
        │    Consul Cluster     │
        │                       │
        │  Services:            │
        │  ✅ ojp-server-1:1059 │
        │  ✅ ojp-server-2:1059 │
        │  ✅ ojp-server-3:1059 │
        │  ❌ ojp-server-4:1059 │ (Unhealthy)
        └───────────────────────┘
                    │
                    │ (Auto-register on startup)
                    │
      ┌─────────────┼─────────────┬─────────────┐
      ▼             ▼             ▼             ▼
┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐
│  OJP    │   │  OJP    │   │  OJP    │   │  OJP    │
│ Server1 │   │ Server2 │   │ Server3 │   │ Server4 │
│ :1059   │   │ :1059   │   │ :1059   │   │ :1059   │
│ Health: │   │ Health: │   │ Health: │   │ Health: │
│ passing │   │ passing │   │ passing │   │ failing │
└─────────┘   └─────────┘   └─────────┘   └─────────┘
      │             │             │
      └─────────────┼─────────────┘
                    ▼
            ┌──────────────┐
            │   Database   │
            │  PostgreSQL  │
            └──────────────┘

Benefits:
✅ Real-time updates via Watch API
✅ Built-in health checking
✅ Service metadata support
✅ Fast propagation of changes
```

### Option 3: Kubernetes Service Discovery

```
┌─────────────────────────────────────────────────────┐
│           Application Pod                           │
│                                                     │
│  Connection URL:                                    │
│  jdbc:ojp[discovery:k8s:ojp-cluster]_              │
│      postgresql://localhost:5432/mydb               │
│                                                     │
│  Properties:                                        │
│  ojp.discovery.k8s.namespace=default                │
│  ojp.discovery.k8s.watchMode=true                   │
└─────────────────────────────────────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │  Kubernetes Endpoints │
        │  API (Watch)          │
        │                       │
        │  Features:            │
        │  • Real-time updates  │
        │  • Pod health         │
        │  • Auto-scaling aware │
        └───────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │  K8s Service          │
        │  "ojp-cluster"        │
        │  Type: ClusterIP      │
        │  Selector: app=ojp    │
        └───────────────────────┘
                    │
      ┌─────────────┼─────────────┬─────────────┐
      ▼             ▼             ▼             ▼
┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
│  OJP Pod 1  │ │  OJP Pod 2  │ │  OJP Pod 3  │ │  OJP Pod 4  │
│             │ │             │ │             │ │             │
│ Status:     │ │ Status:     │ │ Status:     │ │ Status:     │
│ Running     │ │ Running     │ │ Running     │ │ Pending     │
│ Ready: 1/1  │ │ Ready: 1/1  │ │ Ready: 1/1  │ │ Ready: 0/1  │
│ Port: 1059  │ │ Port: 1059  │ │ Port: 1059  │ │ Port: 1059  │
└─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘
      │             │             │
      └─────────────┼─────────────┘
                    ▼
    ┌───────────────────────────────┐
    │   PostgreSQL StatefulSet      │
    │   or External Database        │
    └───────────────────────────────┘

Benefits:
✅ Native K8s integration
✅ Works with HPA (Horizontal Pod Autoscaler)
✅ No additional service registry
✅ Automatic pod health tracking
```

## Graceful Shutdown Flow

```
┌──────────────────────────────────────────────────────────────┐
│                    Admin Action                              │
│          POST /admin/drain?timeout=300                       │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  Phase 1: Deregister from Discovery                          │
│  ┌────────────────────────────────────────────────────┐      │
│  │  • Remove from DNS/Consul/K8s                      │      │
│  │  • Mark as "draining" in service registry          │      │
│  │  • New connections will not be routed here         │      │
│  └────────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  Phase 2: Stop Accepting New Connections                     │
│  ┌────────────────────────────────────────────────────┐      │
│  │  • Server state = DRAINING                         │      │
│  │  • connect() returns UNAVAILABLE                   │      │
│  │  • Existing connections remain active              │      │
│  └────────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  Phase 3: Wait for Active Work to Complete                   │
│  ┌────────────────────────────────────────────────────┐      │
│  │  Monitor:                                          │      │
│  │  • Active connections: 15 → 10 → 5 → 0           │      │
│  │  • Active sessions: 10 → 7 → 3 → 0               │      │
│  │  • Active transactions: 5 → 2 → 0                │      │
│  │                                                    │      │
│  │  Max wait: 300 seconds (configurable)            │      │
│  └────────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  Phase 4: Drain Complete                                     │
│  ┌────────────────────────────────────────────────────┐      │
│  │  • Server state = DRAINED                          │      │
│  │  • All connections closed                          │      │
│  │  • All sessions terminated                         │      │
│  │  • Ready for shutdown                              │      │
│  └────────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  Phase 5: Shutdown                                           │
│  ┌────────────────────────────────────────────────────┐      │
│  │  POST /admin/shutdown                              │      │
│  │  • Server gracefully stops                         │      │
│  │  • No requests lost                                │      │
│  │  • No transactions interrupted                     │      │
│  └────────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────┘

Timeline:
┌────────────────────────────────────────────────────────────┐
│  t=0s    │ Drain initiated                                 │
│  t=5s    │ Deregistered from discovery                     │
│  t=30s   │ Active connections: 15 → 10                     │
│  t=60s   │ Active connections: 10 → 5                      │
│  t=90s   │ Active connections: 5 → 2                       │
│  t=120s  │ Active connections: 2 → 0, transactions: 0      │
│  t=125s  │ Drain complete!                                 │
│  t=130s  │ Shutdown initiated                              │
│  t=135s  │ Server stopped                                  │
└────────────────────────────────────────────────────────────┘
```

## Rolling Update Strategy

```
┌──────────────────────────────────────────────────────────────┐
│  Initial State: 3 servers, all running v1.0.0                │
│                                                              │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐                   │
│  │Server 1 │   │Server 2 │   │Server 3 │                   │
│  │v1.0.0   │   │v1.0.0   │   │v1.0.0   │                   │
│  │✅ Active│   │✅ Active│   │✅ Active│                   │
│  └─────────┘   └─────────┘   └─────────┘                   │
└──────────────────────────────────────────────────────────────┘
                    ▼
┌──────────────────────────────────────────────────────────────┐
│  Step 1: Drain Server 1                                      │
│                                                              │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐                   │
│  │Server 1 │   │Server 2 │   │Server 3 │                   │
│  │v1.0.0   │   │v1.0.0   │   │v1.0.0   │                   │
│  │🔄 DRAIN │   │✅ Active│   │✅ Active│                   │
│  └─────────┘   └─────────┘   └─────────┘                   │
│                                                              │
│  Traffic redistributed to Server 2 & 3                      │
└──────────────────────────────────────────────────────────────┘
                    ▼
┌──────────────────────────────────────────────────────────────┐
│  Step 2: Update Server 1                                     │
│                                                              │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐                   │
│  │Server 1 │   │Server 2 │   │Server 3 │                   │
│  │v1.1.0   │   │v1.0.0   │   │v1.0.0   │                   │
│  │⚙️  UPDATE│   │✅ Active│   │✅ Active│                   │
│  └─────────┘   └─────────┘   └─────────┘                   │
│                                                              │
│  Update in progress...                                       │
└──────────────────────────────────────────────────────────────┘
                    ▼
┌──────────────────────────────────────────────────────────────┐
│  Step 3: Health Check & Add Back                            │
│                                                              │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐                   │
│  │Server 1 │   │Server 2 │   │Server 3 │                   │
│  │v1.1.0   │   │v1.0.0   │   │v1.0.0   │                   │
│  │✅ Active│   │✅ Active│   │✅ Active│                   │
│  └─────────┘   └─────────┘   └─────────┘                   │
│                                                              │
│  Server 1 updated and back in rotation                      │
└──────────────────────────────────────────────────────────────┘
                    ▼
┌──────────────────────────────────────────────────────────────┐
│  Step 4-6: Repeat for Server 2                              │
│  Step 7-9: Repeat for Server 3                              │
│                                                              │
│  Final State: All servers running v1.1.0                    │
│                                                              │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐                   │
│  │Server 1 │   │Server 2 │   │Server 3 │                   │
│  │v1.1.0   │   │v1.1.0   │   │v1.1.0   │                   │
│  │✅ Active│   │✅ Active│   │✅ Active│                   │
│  └─────────┘   └─────────┘   └─────────┘                   │
│                                                              │
│  ✅ Update Complete - Zero Downtime!                        │
└──────────────────────────────────────────────────────────────┘

Configuration:
• maxConcurrentUpdates: 1 (one at a time)
• drainTimeout: 300 seconds
• healthCheckTimeout: 60 seconds
• batchDelay: 30 seconds (wait between servers)
```

## Component Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    JDBC Client Layer                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌────────────────────────────────────────────────────┐    │
│  │         ServiceDiscoveryManager                    │    │
│  │  • Lifecycle management                            │    │
│  │  • Endpoint change notifications                   │    │
│  │  • Fallback handling                               │    │
│  └────────────────────────────────────────────────────┘    │
│                         │                                   │
│                         ▼                                   │
│  ┌────────────────────────────────────────────────────┐    │
│  │         ServiceDiscovery (Interface)               │    │
│  │  • discoverServers()                               │    │
│  │  • startRefresh() / stopRefresh()                  │    │
│  │  • addEndpointChangeListener()                     │    │
│  └────────────────────────────────────────────────────┘    │
│                         │                                   │
│      ┌──────────────────┼──────────────────┐              │
│      ▼                  ▼                  ▼               │
│  ┌────────┐      ┌────────────┐    ┌────────────┐        │
│  │  DNS   │      │   Consul   │    │ Kubernetes │        │
│  │Service │      │  Service   │    │  Service   │        │
│  │Discover│      │  Discover  │    │  Discover  │        │
│  └────────┘      └────────────┘    └────────────┘        │
│                                                             │
│  ┌────────────────────────────────────────────────────┐    │
│  │      MultinodeConnectionManager                    │    │
│  │  • updateEndpoints()                               │    │
│  │  • addEndpoints()                                  │    │
│  │  • removeEndpoints()                               │    │
│  │  • drainAndRemoveEndpoints()                       │    │
│  └────────────────────────────────────────────────────┘    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   OJP Server Layer                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌────────────────────────────────────────────────────┐    │
│  │       ServerLifecycleManager                       │    │
│  │  • enterDrainMode()                                │    │
│  │  • waitForDrain()                                  │    │
│  │  • acceptsNewConnections()                         │    │
│  │  • getDrainStatus()                                │    │
│  └────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌────────────────────────────────────────────────────┐    │
│  │       ConnectionTracker                            │    │
│  │  • register() / unregister()                       │    │
│  │  • getConnectionStats()                            │    │
│  │  • getConnectionsForServer()                       │    │
│  └────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌────────────────────────────────────────────────────┐    │
│  │       Admin API Endpoints                          │    │
│  │  • POST /admin/drain                               │    │
│  │  • GET  /admin/drain/status                        │    │
│  │  • POST /admin/shutdown                            │    │
│  └────────────────────────────────────────────────────┘    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## Timeline and Milestones

```
Week 1-2: Foundation                Week 3-4: DNS Provider
┌────────────────────┐             ┌────────────────────┐
│ ServiceDiscovery   │             │ DnsServiceDiscovery│
│ Interface          │             │ Implementation     │
│                    │             │                    │
│ • Base classes     │             │ • SRV parsing      │
│ • URL parser       │             │ • Periodic refresh │
│ • Config support   │             │ • Integration test │
└────────────────────┘             └────────────────────┘
         ▼                                   ▼

Week 5-6: Graceful Updates         Week 7-9: Service Registry
┌────────────────────┐             ┌────────────────────┐
│ Server Draining    │             │ Consul/etcd        │
│                    │             │ Integration        │
│ • Drain API        │             │                    │
│ • Lifecycle mgmt   │             │ • Real-time watch  │
│ • Connection track │             │ • Health checks    │
└────────────────────┘             └────────────────────┘
         ▼                                   ▼

Week 10-11: Kubernetes             Week 12: Testing
┌────────────────────┐             ┌────────────────────┐
│ K8s Integration    │             │ Production Ready   │
│                    │             │                    │
│ • Endpoints API    │             │ • Load testing     │
│ • Watch mode       │             │ • Chaos testing    │
│ • Examples         │             │ • Documentation    │
└────────────────────┘             └────────────────────┘

                    ▼
        ┌────────────────────┐
        │  Production Release│
        │   v0.4.0           │
        │                    │
        │  ✅ Dynamic Discovery│
        │  ✅ Graceful Updates │
        │  ✅ Zero Downtime    │
        └────────────────────┘
```

---

*This document provides visual representations of the dynamic discovery architecture and safe update strategies proposed for OJP. See detailed analysis documents for complete specifications.*
