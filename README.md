# Pix Wallet Service

## Visão Geral

O **Pix Wallet Service** é um microserviço robusto para gerenciamento de carteiras digitais com suporte a transferências Pix. Desenvolvido com foco em **consistência sob concorrência** e **idempotência**, seguindo princípios de produção mesmo em um ambiente simplificado.

### Características Principais

- **Clean Architecture** com separação clara de responsabilidades
- **Controle de Concorrência** com pessimistic locking e optimistic locking
- **Idempotência** em transferências Pix e webhook events
- **Estado de Máquina** para transferências Pix (PENDING → CONFIRMED/REJECTED)
- **Auditoria Completa** através de sistema de ledger imutável
- **Observabilidade** com métricas, logs estruturados e health checks
- **Testes Abrangentes** incluindo testes de concorrência e idempotência

## Arquitetura

### Clean Architecture

```
├── Domain Layer (Entidades e Regras de Negócio)
│   ├── entities/     # Wallet, PixKey, PixTransfer, LedgerEntry
│   ├── repository/   # Interfaces de repositório
│   └── service/      # AtomicTransferService, IdempotencyService
│
├── Application Layer (Casos de Uso)
│   ├── usecase/      # WalletUseCase, PixTransferUseCase
│   ├── dto/          # DTOs para entrada e saída
│   └── controller/   # WalletController, PixController, MonitoringController
│
└── Infrastructure Layer (Detalhes Técnicos)
    ├── persistence/ # Implementações JPA customizadas com otimizações
    ├── config/      # Configurações do Spring (Cache, Database, Metrics)
    └── logging/     # Logs estruturados e aspectos de observabilidade
```

### Tecnologias Escolhidas

#### **Java 21**
- **LTS mais recente** com melhorias de performance
- **Virtual Threads** para melhor concorrência (preparado para futuro)
- **Records** para DTOs imutáveis e menos boilerplate
- **Pattern Matching** para código mais limpo

#### **Spring Boot 3.2**
- **Ecosystem maduro** com ampla comunidade
- **Auto-configuration** reduz configuração manual
- **Actuator** para observabilidade pronta
- **Transaction Management** robusto para consistência

#### **PostgreSQL**
- **ACID compliance** essencial para transações financeiras
- **Pessimistic locking** com `SELECT FOR UPDATE`
- **Índices avançados** para performance
- **JSON support** para flexibilidade futura

#### **Redis**
- **Cache distribuído** para consultas frequentes
- **Performance** sub-milissegundo para operações em memória
- **Expiration automática** para limpeza de cache

#### **Docker Compose**
- **Ambiente reproduzível** para desenvolvimento
- **Orquestração simples** de múltiplos serviços
- **Health checks** integrados

## Como Executar

### Pré-requisitos
- Java 21
- Docker e Docker Compose
- Maven 3.8+

### 1. Executar com Docker (Recomendado)

```bash
# Clonar o repositório
git clone <repository-url>
cd pix-wallet-service

# Executar todos os serviços
docker-compose up -d

# Verificar logs
docker-compose logs -f pix-wallet-service
```

### 2. Executar Localmente com Dependências Docker

```bash
# Executar apenas dependências (PostgreSQL + Redis)
docker-compose up -d postgres redis

# Executar aplicação com perfil Docker
./mvnw spring-boot:run -Dspring-boot.run.profiles=docker

# Ou compilar e executar
./mvnw clean package
java -jar target/pix-wallet-service-*.jar
```

### 3. Executar em Modo Desenvolvimento (H2)

```bash
# Executar com banco H2 em memória (sem dependências)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Ou definir variável de ambiente
export SPRING_PROFILES_ACTIVE=local
./mvnw spring-boot:run
```

### 3. Executar Testes

```bash
# Todos os testes
./mvnw test

# Apenas testes unitários
./mvnw test -Dtest="**/unit/**"

# Apenas testes de integração
./mvnw test -Dtest="**/integration/**"

# Apenas testes de concorrência
./mvnw test -Dtest="**/concurrency/**"
```

## API Endpoints

### Carteiras

```http
# Criar carteira
POST /api/v1/wallets
Content-Type: application/json
{
  "userId": "user123"
}

# Registrar chave Pix
POST /api/v1/wallets/{id}/pix-keys
Content-Type: application/json
{
  "keyValue": "user@example.com",
  "keyType": "EMAIL"
}

# Consultar saldo atual
GET /api/v1/wallets/{id}/balance

# Consultar saldo histórico
GET /api/v1/wallets/{id}/balance?at=2025-10-09T15:00:00Z

# Depósito
POST /api/v1/wallets/{id}/deposit
Content-Type: application/json
{
  "amount": 100.50,
  "description": "Depósito inicial"
}

# Saque
POST /api/v1/wallets/{id}/withdraw
Content-Type: application/json
{
  "amount": 50.25,
  "description": "Saque de emergência"
}
```

### Transferências Pix

```http
# Iniciar transferência Pix
POST /api/v1/pix/transfers
Idempotency-Key: unique-key-123
Content-Type: application/json
{
  "fromWalletId": "uuid-of-source-wallet",
  "toPixKey": "destination@example.com",
  "amount": 100.00
}

# Webhook para confirmação/rejeição
POST /api/v1/pix/webhook
Content-Type: application/json
{
  "endToEndId": "E123456789",
  "eventId": "event-uuid-123",
  "eventType": "CONFIRMED",
  "occurredAt": "2025-10-09T15:30:00Z"
}
```

## Mecanismos de Segurança e Consistência

### 1. **Controle de Concorrência Atômica**

#### Estruturas Atômicas e Concorrentes
```java
// Contadores atômicos para monitoramento
private final AtomicLong transferCounter = new AtomicLong(0);
private final AtomicInteger activeTransfers = new AtomicInteger(0);

// Estruturas concorrentes para operações thread-safe
private final ConcurrentHashMap<String, AtomicReference<PixTransferStatus>> transferStates;
private final ConcurrentHashMap<UUID, ReentrantReadWriteLock> walletLocks;
```

#### Pessimistic Locking com Fine-Grained Control
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM Wallet w WHERE w.id = :id")
Optional<Wallet> findByIdWithLock(@Param("id") UUID id);

// Fine-grained locking por wallet
ReentrantReadWriteLock walletLock = walletLocks.computeIfAbsent(walletId, k -> new ReentrantReadWriteLock());
```

#### Optimistic Locking + Atomic State Management
```java
@Version
@Column(name = "version")
private Long version;

// Compare-And-Swap para transições de estado
boolean updated = stateRef.compareAndSet(currentStatus, newStatus);
```

### 2. **Idempotência Atômica**

#### Transferências Pix com Double-Checked Locking
```java
// Atomic idempotency check com cache em memória
AtomicReference<IdempotencyRecord> cachedRecord = processingCache.get(cacheKey);

// Double-checked locking pattern
Optional<IdempotencyRecord> existing = findExistingRecord(scope, idempotencyKey);
if (existing.isPresent()) {
    return existing.get();
}
```

#### Webhook Events com Atomic Processing
```java
// Atomic state transition usando CAS
boolean stateUpdated = atomicTransferService.updateTransferStateAtomically(
    endToEndId, targetStatus, reason
);

// Idempotency record com transação SERIALIZABLE
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
public IdempotencyRecord saveRecordAtomically(...) {
    // Implementation with atomic operations
}
```

### 3. **Máquina de Estados Atômica**

```
PENDING ──→ CONFIRMED (crédito atômico na carteira destino)
   │
   └────→ REJECTED (refund atômico na carteira origem)
```

#### State Transitions com Compare-And-Swap
```java
// Atomic state management em memória
private final ConcurrentHashMap<String, AtomicReference<PixTransferStatus>> transferStates;

// Validação e transição atômica
private boolean isValidTransition(PixTransferStatus from, PixTransferStatus to) {
    return switch (from) {
        case PENDING -> to == PixTransferStatus.CONFIRMED || to == PixTransferStatus.REJECTED;
        case CONFIRMED, REJECTED -> false; // Terminal states
    };
}
```

### 4. **Sistema de Ledger Atômico**

- **Entradas imutáveis** com operações thread-safe
- **Saldo calculado** atomicamente a partir do ledger
- **Balance histórico** com read locks para consistência
- **Transaction ID** único com contadores atômicos
- **Fine-grained locking** por wallet para máxima performance

## Observabilidade

### Métricas (Prometheus)
- `wallet.created` - Carteiras criadas
- `wallet.deposit` - Depósitos processados
- `wallet.withdrawal` - Saques processados
- `pix.transfer` - Transferências Pix
- `pix.webhook` - Eventos webhook processados

### Health Checks e Monitoramento Atômico
```http
# Health checks padrão
GET /actuator/health
GET /actuator/metrics
GET /actuator/prometheus

# Estatísticas atômicas detalhadas
GET /api/v1/monitoring/atomic-stats
GET /api/v1/monitoring/system-health

# Operações de limpeza
POST /api/v1/monitoring/cleanup
```

#### Exemplo de Resposta de Estatísticas Atômicas
```json
{
  "timestamp": "2025-10-09T15:30:00Z",
  "monitoringRequestNumber": 42,
  "walletStats": {
    "walletsCreated": 150,
    "depositsProcessed": 2340,
    "withdrawalsProcessed": 1876,
    "pixKeysRegistered": 287,
    "activeLocks": 3
  },
  "transferStats": {
    "totalTransfers": 1234,
    "successfulTransfers": 1230,
    "failedTransfers": 4,
    "activeTransfers": 2,
    "successRate": 99.67
  },
  "idempotencyStats": {
    "cacheSize": 45,
    "lockCount": 1,
    "cleanupInProgress": false
  }
}
```

### Logs Estruturados e Observabilidade

#### Estrutura de Logs JSON
```json
{
  "timestamp": "2025-10-09T15:30:00.123Z",
  "level": "INFO",
  "logger": "com.finaya.pixwallet.transfer",
  "message": "Pix transfer initiated",
  "event": "transfer_initiated",
  "endToEndId": "E123456789",
  "idempotencyKey": "uuid-key-123",
  "fromWalletId": "wallet-uuid",
  "toPixKey": "user@example.com",
  "amount": "100.00",
  "correlationId": "req-uuid-456",
  "transactionId": "TX-789"
}
```

#### Aspectos de Logging Automático
```java
@Around("execution(* com.finaya.pixwallet.application.usecase.*.*(..))")
public Object logUseCaseExecution(ProceedingJoinPoint joinPoint) {
    // Automatic correlation ID generation
    // Performance monitoring
    // Error context capture
    // Structured logging
}
```

#### Monitoramento de Performance de Queries
```java
// Query optimization com logs estruturados
structuredLogger.logPerformanceMetric("database_query", duration, queryString);

// Detecção automática de queries lentas
if (duration > 1000) {
    logger.warn("Slow query detected - Duration: {}ms", duration);
}
```

### Monitoramento com Grafana
- Dashboard disponível em `http://localhost:3000`
- Usuário: `admin` / Senha: `admin123`
- Métricas em tempo real do Prometheus

## Estratégia de Testes

### 1. **Testes Unitários**
- Validação de regras de negócio
- Entidades e value objects
- Casos extremos e validações

### 2. **Testes de Integração**
- APIs end-to-end
- Integração com banco de dados
- Cenários de sucesso e erro

### 3. **Testes de Concorrência**
```java
@Test
void shouldHandleConcurrentTransfersWithSameIdempotencyKey() {
    // CountDownLatch para sincronização
    // 10 threads simultâneas
    // Verificação de debit único
}
```

### 4. **Testes de Idempotência**
```java
@Test
void shouldProcessWebhookEventsIdempotently() {
    // Mesmo eventId processado múltiplas vezes
    // Verificação de resultado idêntico
}
```

## Cenários Críticos Tratados

### 1. **Duplo Disparo**
- Duas requisições simultâneas com mesmo `Idempotency-Key`
- **Resultado:** Apenas um débito efetivo
- **Mecanismo:** Unique constraint + retry logic

### 2. **Webhook Duplicado**
- Múltiplos POST webhook com mesmo `eventId`
- **Resultado:** Processamento único
- **Mecanismo:** Tabela de idempotência

### 3. **Ordem Trocada**
- REJECTED chegando antes de CONFIRMED
- **Resultado:** Máquina de estados respeitada
- **Mecanismo:** Estado validation

### 4. **Reprocessamento**
- Execução at-least-once de eventos
- **Resultado:** Saldo final consistente
- **Mecanismo:** Idempotência + ledger imutável

## Melhorias Futuras

### Curto Prazo
- [ ] **Rate Limiting** para APIs
- [ ] **Circuit Breaker** para resilência
- [ ] **Swagger/OpenAPI** documentation
- [ ] **Database Migration** com Flyway
- [ ] **Atomic Batch Operations** para múltiplas transferências
- [ ] **Lock-Free Data Structures** para ainda maior performance

### Médio Prazo
- [ ] **Event Sourcing** completo
- [ ] **CQRS** para segregação de comandos/queries
- [ ] **Distributed Tracing** com Jaeger
- [ ] **Message Queue** (RabbitMQ/Kafka) para webhooks

### Longo Prazo
- [ ] **Multi-tenancy** para múltiplos bancos
- [ ] **Kubernetes** deployment
- [ ] **OAuth2/JWT** authentication
- [ ] **Regulatory Compliance** (PCI-DSS)

## Troubleshooting

### Problemas Comuns

1. **Porta já em uso**
```bash
# Verificar processos
lsof -i :8080
# Matar processo
kill -9 <PID>
```

2. **Database connection error**
```bash
# Verificar status do PostgreSQL
docker-compose ps postgres
# Logs do banco
docker-compose logs postgres
```

3. **Tests failing**
```bash
# Limpar e recompilar
./mvnw clean compile test
# Verificar profile de test
export SPRING_PROFILES_ACTIVE=test
```

## Performance

### Benchmarks Esperados
- **Latência P50:** < 50ms para operações simples
- **Latência P99:** < 200ms para transferências
- **Throughput:** > 1000 ops/seg para consultas
- **Concurrent Users:** > 100 simultâneos

### Otimizações Implementadas
- **Connection Pooling** (HikariCP)
- **Query Optimization** com índices apropriados
- **Cache Strategy** para consultas frequentes
- **Lazy Loading** para relacionamentos JPA

## Decisões de Design

### Por que PostgreSQL?
- **ACID compliance** essencial para transações financeiras
- **Mature ecosystem** com Spring Boot
- **Advanced indexing** para performance
- **JSON support** para flexibilidade

### Por que Pessimistic Locking?
- **Garantia de consistência** em cenários de alta concorrência
- **Simplicity** comparado a compensation patterns
- **Predictable behavior** para transações financeiras

### Por que Clean Architecture?
- **Testability** alta com dependências invertidas
- **Maintainability** com separation of concerns
- **Flexibility** para mudanças futuras
- **Domain-driven** design principles

### Por que Operações Atômicas?
- **Thread-Safety** garantida sem overhead de sincronização global
- **Lock-Free Operations** onde possível usando CAS (Compare-And-Swap)
- **Fine-Grained Locking** apenas onde necessário (por wallet, por transfer)
- **Memory Consistency** com estruturas concorrentes otimizadas
- **Performance** superior sob alta concorrência
- **Deadlock Prevention** com hierarquia de locks bem definida

### Por que Camadas de Infrastructure Customizadas?

#### **Persistence Layer**
- **Repository Implementations** customizadas com otimizações específicas
- **Query Performance Monitoring** automático com detecção de queries lentas
- **Database Health Checks** detalhados com métricas de performance
- **Connection Pool Monitoring** para identificar gargalos
- **Automatic Statistics Updates** para otimização de queries

#### **Logging Layer**
- **Structured JSON Logging** para facilitar análise e correlação
- **Automatic Correlation IDs** para rastreamento de requests end-to-end
- **Performance Metrics** automáticos em todos os métodos críticos
- **Aspect-Oriented Logging** para captura sem poluir código de negócio
- **Error Context Enrichment** com informações detalhadas para debugging

#### **Benefícios da Implementação Customizada**
- **Observabilidade Total** - visibilidade completa do sistema em produção
- **Performance Optimization** - identificação proativa de gargalos
- **Debugging Facilitado** - correlation IDs e context enrichment
- **Monitoring Proativo** - detecção automática de problemas
- **Production Readiness** - health checks e métricas detalhadas
