# Dynamic OJP Server Discovery - Visual Architecture

## Current Architecture (Static Configuration)

```mermaid
graph TB
    subgraph App["Application (JDBC Client)"]
        URL["Connection URL:<br/>jdbc:ojp[server1:1059,server2:1059,server3:1059]_<br/>postgresql://localhost:5432/mydb<br/>❌ Static - requires restart to change"]
    end
    
    App --> StaticList["Static Server List<br/>[Hard-coded in URL]"]
    StaticList --> Server1["OJP Server1<br/>:1059"]
    StaticList --> Server2["OJP Server2<br/>:1059"]
    StaticList --> Server3["OJP Server3<br/>:1059"]
    
    Server1 --> DB["Database<br/>PostgreSQL"]
    Server2 --> DB
    Server3 --> DB
    
    style App fill:#f9f,stroke:#333,stroke-width:2px
    style StaticList fill:#faa,stroke:#333,stroke-width:2px
    style DB fill:#aff,stroke:#333,stroke-width:2px
```

## Proposed Architecture (Dynamic Discovery)

### Option 1: DNS-Based Discovery

```mermaid
graph TB
    subgraph App["Application (JDBC Client)"]
        URL["Connection URL:<br/>jdbc:ojp[discovery:dns:ojp-cluster.example.com]_<br/>postgresql://localhost:5432/mydb<br/>✅ Dynamic - no restart needed"]
    end
    
    App --> Discovery["DNS Service Discovery<br/>(SRV Records)<br/>Refresh: 30s"]
    Discovery -->|"Query _ojp._tcp.ojp-cluster.example.com"| DNSServer["DNS Server<br/>SRV Records:<br/>→ server1:1059<br/>→ server2:1059<br/>→ server3:1059<br/>→ server4:1059 (NEW!)"]
    
    DNSServer --> Server1["OJP Server1<br/>:1059<br/>✅ Active"]
    DNSServer --> Server2["OJP Server2<br/>:1059<br/>✅ Active"]
    DNSServer --> Server3["OJP Server3<br/>:1059<br/>✅ Active"]
    DNSServer --> Server4["OJP Server4<br/>:1059<br/>✅ NEW!"]
    
    Server1 --> DB["Database<br/>PostgreSQL"]
    Server2 --> DB
    Server3 --> DB
    Server4 --> DB
    
    style App fill:#9f9,stroke:#333,stroke-width:2px
    style Discovery fill:#ff9,stroke:#333,stroke-width:2px
    style DNSServer fill:#f9f,stroke:#333,stroke-width:2px
    style Server4 fill:#9ff,stroke:#333,stroke-width:2px
    style DB fill:#aff,stroke:#333,stroke-width:2px
```

**Benefits:**
- ✅ Automatic discovery of new servers
- ✅ No application restart required
- ✅ Low operational overhead
- ✅ Works with existing DNS infrastructure

### Option 2: Consul Service Discovery

```mermaid
graph TB
    subgraph App["Application (JDBC Client)"]
        URL["Connection URL:<br/>jdbc:ojp[discovery:consul:ojp-server]_<br/>postgresql://localhost:5432/mydb<br/><br/>Properties:<br/>ojp.discovery.consul.host=consul.example.com<br/>ojp.discovery.refresh.interval=10"]
    end
    
    App --> ConsulDiscovery["Consul Service Discovery<br/><br/>Features:<br/>• Health checks<br/>• Watch API (real-time)<br/>• Service metadata"]
    ConsulDiscovery -->|"Query healthy instances"| ConsulCluster["Consul Cluster<br/><br/>Services:<br/>✅ ojp-server-1:1059<br/>✅ ojp-server-2:1059<br/>✅ ojp-server-3:1059<br/>❌ ojp-server-4:1059 (Unhealthy)"]
    
    ConsulCluster -.->|"Auto-register on startup"| Server1
    ConsulCluster -.->|"Auto-register on startup"| Server2
    ConsulCluster -.->|"Auto-register on startup"| Server3
    ConsulCluster -.->|"Auto-register on startup"| Server4
    
    ConsulCluster --> Server1["OJP Server1<br/>:1059<br/>Health: passing"]
    ConsulCluster --> Server2["OJP Server2<br/>:1059<br/>Health: passing"]
    ConsulCluster --> Server3["OJP Server3<br/>:1059<br/>Health: passing"]
    
    Server4["OJP Server4<br/>:1059<br/>Health: failing"]
    
    Server1 --> DB["Database<br/>PostgreSQL"]
    Server2 --> DB
    Server3 --> DB
    
    style App fill:#9f9,stroke:#333,stroke-width:2px
    style ConsulDiscovery fill:#ff9,stroke:#333,stroke-width:2px
    style ConsulCluster fill:#f9f,stroke:#333,stroke-width:2px
    style Server4 fill:#faa,stroke:#333,stroke-width:2px
    style DB fill:#aff,stroke:#333,stroke-width:2px
```

**Benefits:**
- ✅ Real-time updates via Watch API
- ✅ Built-in health checking
- ✅ Service metadata support
- ✅ Fast propagation of changes

### Option 3: Kubernetes Service Discovery

```mermaid
graph TB
    subgraph AppPod["Application Pod"]
        URL["Connection URL:<br/>jdbc:ojp[discovery:k8s:ojp-cluster]_<br/>postgresql://localhost:5432/mydb<br/><br/>Properties:<br/>ojp.discovery.k8s.namespace=default<br/>ojp.discovery.k8s.watchMode=true"]
    end
    
    AppPod --> K8sAPI["Kubernetes Endpoints API (Watch)<br/><br/>Features:<br/>• Real-time updates<br/>• Pod health<br/>• Auto-scaling aware"]
    K8sAPI --> K8sService["K8s Service 'ojp-cluster'<br/>Type: ClusterIP<br/>Selector: app=ojp"]
    
    K8sService --> Pod1["OJP Pod 1<br/>Status: Running<br/>Ready: 1/1<br/>Port: 1059"]
    K8sService --> Pod2["OJP Pod 2<br/>Status: Running<br/>Ready: 1/1<br/>Port: 1059"]
    K8sService --> Pod3["OJP Pod 3<br/>Status: Running<br/>Ready: 1/1<br/>Port: 1059"]
    K8sService -.-> Pod4["OJP Pod 4<br/>Status: Pending<br/>Ready: 0/1<br/>Port: 1059"]
    
    Pod1 --> PSQL["PostgreSQL StatefulSet<br/>or External Database"]
    Pod2 --> PSQL
    Pod3 --> PSQL
    
    style AppPod fill:#9f9,stroke:#333,stroke-width:2px
    style K8sAPI fill:#ff9,stroke:#333,stroke-width:2px
    style K8sService fill:#f9f,stroke:#333,stroke-width:2px
    style Pod4 fill:#faa,stroke:#333,stroke-width:2px,stroke-dasharray: 5 5
    style PSQL fill:#aff,stroke:#333,stroke-width:2px
```

**Benefits:**
- ✅ Native K8s integration
- ✅ Works with HPA (Horizontal Pod Autoscaler)
- ✅ No additional service registry
- ✅ Automatic pod health tracking

## Graceful Shutdown Flow

```mermaid
sequenceDiagram
    participant Admin
    participant Server as OJP Server
    participant Discovery as Service Discovery
    participant Connections as Active Connections
    participant Transactions as Active Transactions
    
    Admin->>Server: POST /admin/drain?timeout=300
    
    rect rgb(255, 240, 240)
        Note over Server,Discovery: Phase 1: Deregister
        Server->>Discovery: Remove from DNS/Consul/K8s
        Server->>Discovery: Mark as "draining"
        Note over Discovery: New connections not routed here
    end
    
    rect rgb(240, 255, 240)
        Note over Server: Phase 2: Stop New Connections
        Server->>Server: State = DRAINING
        Note over Server: connect() returns UNAVAILABLE<br/>Existing connections remain active
    end
    
    rect rgb(240, 240, 255)
        Note over Server,Transactions: Phase 3: Wait for Completion
        loop Monitor every second (max 300s)
            Server->>Connections: Check active: 15→10→5→0
            Server->>Transactions: Check active: 5→2→0
            alt All work complete
                Server->>Server: State = DRAINED
            end
        end
    end
    
    rect rgb(255, 255, 240)
        Note over Server: Phase 4: Drain Complete
        Server->>Admin: 200 OK - Drain complete
        Note over Server: All connections closed<br/>All sessions terminated<br/>Ready for shutdown
    end
    
    Admin->>Server: POST /admin/shutdown
    Server->>Server: Graceful stop
    Note over Server: Phase 5: Server stopped<br/>✅ No requests lost
```

**Timeline:**
```mermaid
gantt
    title Graceful Shutdown Timeline
    dateFormat ss
    axisFormat %Ss
    
    section Drain Process
    Drain initiated           :milestone, m1, 00, 0s
    Deregister from discovery :active, t1, 00, 5s
    Active conns: 15→10       :active, t2, 05, 25s
    Active conns: 10→5        :active, t3, 30, 30s
    Active conns: 5→2         :active, t4, 60, 30s
    Active conns: 2→0         :active, t5, 90, 30s
    Drain complete            :milestone, m2, 120, 0s
    
    section Shutdown
    Shutdown initiated        :crit, s1, 125, 5s
    Server stopped            :milestone, m3, 130, 0s
```

## Rolling Update Strategy

```mermaid
stateDiagram-v2
    [*] --> Initial: 3 servers v1.0.0
    
    state Initial {
        [*] --> S1_v1: Server 1 (v1.0.0) ✅
        [*] --> S2_v1: Server 2 (v1.0.0) ✅
        [*] --> S3_v1: Server 3 (v1.0.0) ✅
    }
    
    Initial --> Drain1: Step 1: Drain Server 1
    
    state Drain1 {
        [*] --> S1_drain: Server 1 (v1.0.0) 🔄 DRAIN
        [*] --> S2_active: Server 2 (v1.0.0) ✅
        [*] --> S3_active: Server 3 (v1.0.0) ✅
        note right of S1_drain: Traffic redistributed to Server 2 & 3
    }
    
    Drain1 --> Update1: Step 2: Update Server 1
    
    state Update1 {
        [*] --> S1_update: Server 1 (v1.1.0) ⚙️ UPDATE
        [*] --> S2_still: Server 2 (v1.0.0) ✅
        [*] --> S3_still: Server 3 (v1.0.0) ✅
        note right of S1_update: Update in progress...
    }
    
    Update1 --> Validate1: Step 3: Health Check
    
    state Validate1 {
        [*] --> S1_new: Server 1 (v1.1.0) ✅
        [*] --> S2_old: Server 2 (v1.0.0) ✅
        [*] --> S3_old: Server 3 (v1.0.0) ✅
        note right of S1_new: Server 1 updated and back in rotation
    }
    
    Validate1 --> RepeatProcess: Steps 4-9
    RepeatProcess --> Final: All Updated
    
    state Final {
        [*] --> S1_final: Server 1 (v1.1.0) ✅
        [*] --> S2_final: Server 2 (v1.1.0) ✅
        [*] --> S3_final: Server 3 (v1.1.0) ✅
        note right of S1_final: ✅ Update Complete - Zero Downtime!
    }
    
    Final --> [*]
```

**Configuration:**
- `maxConcurrentUpdates: 1` (one at a time)
- `drainTimeout: 300 seconds`
- `healthCheckTimeout: 60 seconds`
- `batchDelay: 30 seconds` (wait between servers)

## Component Architecture

```mermaid
graph TB
    subgraph ClientLayer["JDBC Client Layer"]
        SDM["ServiceDiscoveryManager<br/>• Lifecycle management<br/>• Endpoint change notifications<br/>• Fallback handling"]
        
        SDI["ServiceDiscovery Interface<br/>• discoverServers()<br/>• startRefresh() / stopRefresh()<br/>• addEndpointChangeListener()"]
        
        DNS["DNS Service<br/>Discovery"]
        Consul["Consul Service<br/>Discovery"]
        K8s["Kubernetes Service<br/>Discovery"]
        
        MCM["MultinodeConnectionManager<br/>• updateEndpoints()<br/>• addEndpoints()<br/>• removeEndpoints()<br/>• drainAndRemoveEndpoints()"]
    end
    
    SDM --> SDI
    SDI --> DNS
    SDI --> Consul
    SDI --> K8s
    SDM --> MCM
    
    subgraph ServerLayer["OJP Server Layer"]
        SLM["ServerLifecycleManager<br/>• enterDrainMode()<br/>• waitForDrain()<br/>• acceptsNewConnections()<br/>• getDrainStatus()"]
        
        CT["ConnectionTracker<br/>• register() / unregister()<br/>• getConnectionStats()<br/>• getConnectionsForServer()"]
        
        Admin["Admin API Endpoints<br/>• POST /admin/drain<br/>• GET /admin/drain/status<br/>• POST /admin/shutdown"]
    end
    
    MCM -.->|gRPC| SLM
    SLM --> CT
    Admin --> SLM
    
    style ClientLayer fill:#e1f5ff,stroke:#333,stroke-width:2px
    style ServerLayer fill:#fff5e1,stroke:#333,stroke-width:2px
    style SDI fill:#ff9,stroke:#333,stroke-width:2px
    style MCM fill:#9f9,stroke:#333,stroke-width:2px
    style SLM fill:#f99,stroke:#333,stroke-width:2px
```

## Timeline and Milestones

```mermaid
gantt
    title OJP Dynamic Discovery Implementation Timeline
    dateFormat YYYY-MM-DD
    section Phase 1: Foundation
    ServiceDiscovery Interface     :p1a, 2026-01-06, 7d
    Base classes                    :p1b, after p1a, 3d
    URL parser extensions           :p1c, after p1a, 4d
    Configuration support           :p1d, after p1c, 3d
    
    section Phase 2: DNS Provider
    DnsServiceDiscovery impl        :p2a, after p1d, 7d
    SRV record parsing              :p2b, after p2a, 3d
    Periodic refresh                :p2c, after p2a, 3d
    Integration tests               :p2d, after p2c, 4d
    
    section Phase 3: Graceful Updates
    Server Drain API                :p3a, after p2d, 5d
    Lifecycle management            :p3b, after p3a, 4d
    Connection tracking             :p3c, after p3a, 5d
    
    section Phase 4: Service Registry
    Consul integration              :p4a, after p3c, 10d
    Real-time watch                 :p4b, after p4a, 5d
    Health checks                   :p4c, after p4a, 6d
    
    section Phase 5: Kubernetes
    K8s Endpoints API               :p5a, after p4c, 7d
    Watch mode                      :p5b, after p5a, 4d
    Examples & docs                 :p5c, after p5b, 3d
    
    section Phase 6: Testing
    Load testing                    :p6a, after p5c, 3d
    Chaos testing                   :p6b, after p6a, 2d
    Documentation                   :p6c, after p6a, 2d
    
    section Milestones
    Phase 1 Complete                :milestone, m1, after p1d, 0d
    Phase 2 Complete (DNS Ready)    :milestone, m2, after p2d, 0d
    Phase 3 Complete (Graceful)     :milestone, m3, after p3c, 0d
    Phase 4 Complete (Consul)       :milestone, m4, after p4c, 0d
    Phase 5 Complete (K8s)          :milestone, m5, after p5c, 0d
    Production Release v0.4.0       :milestone, m6, after p6c, 0d
```

---

*This document provides visual representations of the dynamic discovery architecture and safe update strategies proposed for OJP. See detailed analysis documents for complete specifications.*
