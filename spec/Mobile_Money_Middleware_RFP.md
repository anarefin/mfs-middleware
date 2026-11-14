Mobile Money Platform -- Middleware Requirements Document

Request for Proposal (RFP): Integration Middleware, Source-Code
Ownership and Environment Control

Issued by: upay\| Version: 0.1 (Draft) \| Date: \[DD Month YYYY\] \|
Proposal due: \[Date\] \|

Contact: \[, \]

# 1. Purpose of this document

upay is building a Mobile Money (MFS) platform. We are seeking a vendor
to design, build, deliver and hand over the middleware / integration
layer of this platform. This document describes what we need so that
vendors can submit a technical and commercial proposal.

Three principles drive every requirement below:

We own the code. All source code, configurations, schemas, scripts and
documentation produced for us are our property, with no licence fees or
lock-in on the core middleware.

We have full control. We must be able to build, deploy, change, scale,
monitor and troubleshoot the middleware ourselves, without depending on
the vendor for routine operations.

We control the environment. The vendor delivers the tooling (CI/CD,
infrastructure as code, observability, configuration and secrets
management) that lets us run and govern every environment.

# 2. Background and target architecture

The platform is organised in the following layers (see the architecture
diagram, Annex A).

Access channels (inbound): Customer App, Partner App, USSD gateways for
GP, Robi, Teletalk and Banglalink, Web Checkout Page, Org Portal and SP
(Service Provider) Portal. Protocols: SOAP (via App Middleware), SMPP,
HTTPS.

Notification: Email server (SMTP), SMSC (SMPP 3.4) and App (push)
server.

Core platform zones:

Operation Zone: Digital View, Digital Foundary

Data Zone: Database (DB)

DMZ Zone: NSLB (load balancer), SFTP

Application Zone: Digital Wallet Microservice, Digital Payment
Microservice

Infrastructure: underlying compute, network and storage

External integrations (outbound/bidirectional): compliance and identity,
telco top-up, bill and utility payment, merchant payment, bank and card
payments, remittance, ATM, interoperability and data export. Full
inventory in Section 5.

# 3. Scope of work

## 3.1 In scope

The vendor will deliver, for the layer between the access channels, the
core wallet and payment microservices, and all external parties:

API gateway and integration/orchestration layer (including the App
Middleware for mobile apps)

Protocol adapters and connectors for every integration in Section 5

USSD and SMPP channel handling for the four MNO gateways

Notification service (SMS, email, push)

Message transformation, routing, validation, idempotency, retry and
error handling

Reconciliation and settlement file/message handling (e.g. MT940/MT942
from the trust bank)

Transaction and audit logging, monitoring, alerting and operational
dashboards

Middleware administration console

Environment tooling: CI/CD, infrastructure as code,
configuration/secrets management, observability stack

Testing assets: simulators/mocks of every external party, automated test
suites, performance test scripts

Documentation, training and knowledge transfer

Warranty and optional support after go-live

## 3.2 Out of scope (to be confirmed)

Core wallet ledger, Digital Wallet and Digital Payment microservices,
Digital View and Digital Foundary (provided by \[Core platform vendor /
in-house\]); the middleware must integrate with them through documented
APIs.

Mobile app and portal front-end development.

Commercial agreements and certification with external parties (we will
lead these; the vendor must support technical certification).

# 4. Ownership, intellectual property and control (mandatory)

These are pass/fail requirements. Proposals that cannot comply will be
disqualified or must state exceptions clearly.

| ID \| Requirement \|

| --- \| --- \|

| OWN-01 \| All custom source code, configurations, scripts, database
  schemas, API definitions, test cases, runbooks and documentation
  created under this engagement are the exclusive property of upay upon
  payment, with full IP assignment. \|

| OWN-02 \| Source code is delivered into a repository owned and
  administered by us (e.g. our GitLab/GitHub/Azure DevOps organisation)
  from day one, with commit history preserved. The vendor works inside
  our repositories. \|

| OWN-03 \| No proprietary, closed-source runtime component may be
  required for the core middleware to run. Where the vendor proposes a
  commercial product, it must be listed with licence type, cost and an
  exit path. Preference is for open-source components with permissive
  licences (Apache 2.0, MIT, BSD, MPL). Copyleft licences (GPL/AGPL)
  must be disclosed and approved in writing. \|

| OWN-04 \| Perpetual, irrevocable, worldwide, royalty-free right to
  use, modify, extend, deploy and sublicense (to our affiliates and
  other contractors) any pre-existing vendor IP embedded in the
  solution. \|

| OWN-05 \| A full Software Bill of Materials (SBOM) for every release,
  listing all third-party libraries, versions and licences. \|

| OWN-06 \| Build reproducibility: we must be able to build and deploy
  every release from source on our own infrastructure using the
  delivered pipelines, with no vendor-hosted build, licence server or
  remote activation. \|

| OWN-07 \| No vendor back doors, hard-coded credentials, remote access
  or telemetry. Vendor access to our environments is only through our
  access-control process, time-bound and audited. \|

| OWN-08 \| All credentials, keys, certificates and admin rights are
  held by us. Vendor accounts are created and revoked by us. \|

| OWN-09 \| Complete handover package at each milestone and at project
  close (see Section 11.4). Source-code escrow is not an acceptable
  substitute for OWN-02. \|

| OWN-10 \| Vendor personnel may not reuse our confidential business
  logic, integration specifications or data for other clients.
  Confidentiality and non-compete terms to be defined in the contract.
  \|

| OWN-11 \| Data residency: all data, logs and backups remain within
  \[Bangladesh / approved locations\] and under our control. \|

# 5. Integration inventory

The middleware must provide production-grade connectors for each of the
following. Vendors should confirm prior experience with each category
and state any assumptions.

## 5.1 Channels (inbound)

| Integration \| Protocol \| Notes \|

| --- \| --- \| --- \|

| Customer App, Agent App, Merchant App, Distributor App, DSO App,
  Partner App \| SOAP/REST via App Middleware \| Session,
  authentication, request routing, throttling \|

| GP, Robi, Teletalk, Banglalink USSD gateways \| SMPP \| Session-based
  menus, timeouts, MNO-specific behaviour, short-code management \|

| Web Checkout Page \| HTTPS \| Merchant checkout, redirect and callback
  handling \|

| Org Portal, SP Portal \| HTTPS \| Bulk disbursement, service-provider
  operations \|

## 5.2 Notification

| Integration \| Protocol \|

| --- \| --- \|

| Email server \| SMTP \|

| SMSC \| SMPP 3.4 \|

| App (push) server \| Vendor to propose (e.g. FCM/APNs via our app
  server) \|

## 5.3 Compliance, identity and bank reconciliation

| Integration \| Protocol \| Notes \|

| --- \| --- \| --- \|

| AML system \| SFTP \| Transaction file export for AML checks;
  response/alert handling \|

| Trust bank reconciliation \| MT940 / MT942 \| Statement ingestion,
  parsing, matching \|

| Bangladesh Election Commission identity validation \| SOAP / REST \|
  eKYC / NID verification \|

## 5.4 Telco top-up (prepaid and postpaid)

| Integration \| Protocol \|

| --- \| --- \|

| GP OCS, Robi OCS, Teletalk OCS, Banglalink OCS \| REST \|

## 5.5 Bill, utility and merchant payments

| Integration \| Protocol \| Notes \|

| --- \| --- \| --- \|

| Utility aggregator (about 17 gas/water/electricity billers) \| REST \|
  Bidirectional \|

| Bill aggregators: SSLCommerz, eKpay, aggregator 3, aggregator 4 (about
  40 billers: cable TV, school, internet, toll etc.) \| REST \|
  Bidirectional \|

| Merchant system \| SOAP \| Merchant payment \|

## 5.6 Banks, cards, remittance, ATM and interoperability

| Integration \| Protocol \| Notes \|

| --- \| --- \| --- \|

| Bank gateway (about 20 partner banks): C2W, AVL, card payments \| SOAP
  / REST \| Bidirectional \|

| BanglaQR & NPSB interoperability \| ISO 20022 \| QR payments \|

| CyberSource (Add Money) \| HTTPS \| Card funding \|

| UCB IMT (international remittance) \| HTTPS \| Inbound remittance \|

| UCB ATM gateway (cash in / cash out) \| SOAP \| ATM transactions \|

| Data Warehouse (Data Openness) \| SFTP / Kafka \| Event streaming and
  batch export \|

The architecture must allow new integrations to be added by our own team
through configuration and a documented adapter framework, without vendor
involvement.

# 6. Functional requirements

## 6.1 API gateway and integration

FR-01 Single managed entry point for all channels with authentication
(OAuth2/JWT, mTLS, API keys, HMAC signatures as required),
authorisation, rate limiting, throttling and quotas per channel/partner.

FR-02 Protocol support: REST/JSON, SOAP/XML, SMPP (v3.4), SMTP, SFTP,
ISO 20022, SWIFT MT (MT940/MT942) parsing, Kafka, HTTPS
callbacks/webhooks.

FR-03 Message transformation and canonical data model between channels,
core microservices and external parties.

FR-04 Orchestration of multi-step flows (e.g. wallet debit, external
call, then confirmation or reversal) with saga/compensation patterns.

FR-05 Idempotency keys and duplicate-request detection on all financial
operations.

FR-06 Configurable timeout, retry, circuit-breaker and fallback policies
per integration.

FR-07 Automatic reversal and handling of pending/unknown transaction
states, including timeout-driven status inquiry.

FR-08 API versioning and backward compatibility; API catalogue with
OpenAPI/WSDL specifications published for every endpoint.

FR-09 Routing rules configurable at runtime (e.g. biller, bank or
operator routing, failover between aggregators).

## 6.2 USSD and SMPP

FR-10 USSD menu engine with configurable menus, languages (Bangla and
English), session handling and per-MNO adaptations, managed by our team
without code changes where possible.

FR-11 SMPP connection management: bind, enquire_link, windowing,
throttling, reconnect, delivery receipts.

## 6.3 Notification service

FR-12 Event-driven notifications via SMS, email and push, with
templates, languages, scheduling, retry and delivery status tracking.

FR-13 Template and rules management through an admin UI.

## 6.4 Reconciliation and settlement support

FR-14 Automated ingestion of bank statements (MT940/MT942) and partner
settlement files; matching against transaction logs; exception reports.

FR-15 Daily, intraday and on-demand reconciliation reports per partner.

## 6.5 Compliance support

FR-16 Transaction feed to the AML system and handling of block/hold
instructions returned.

FR-17 Identity validation calls with consent logging and masking of
sensitive fields.

FR-18 Full, immutable audit trail of every request, response,
transformation and operator action, searchable by transaction ID,
MSISDN, partner and time.

## 6.6 Administration console

FR-19 Web console for endpoint and partner onboarding, credentials and
certificate management, routing and throttling rules, template
management, transaction search and status, manual retry/reversal (with
maker-checker), and role-based access control.

FR-20 Maker-checker approval on all sensitive configuration changes.

## 6.7 Data and events

FR-21 Publish transaction and event streams to the Data Warehouse (Kafka
and SFTP batch).

FR-22 Configurable data retention and archival.

# 7. Non-functional requirements

| ID \| Area \| Requirement (targets to be confirmed in proposal) \|

| --- \| --- \| --- \|

| NFR-01 \| Throughput \| Sustain \[X\] TPS peak and \[Y\] TPS average
  with 3x headroom; vendor to propose sizing. Design for horizontal
  scaling. \|

| NFR-02 \| Latency \| Middleware overhead under \[X\] ms at the 95th
  percentile, excluding external party time. \|

| NFR-03 \| Availability \| 99.95% or better for the middleware; no
  single point of failure; active-active across \[two\] data centres. \|

| NFR-04 \| Disaster recovery \| RPO of \[0 to 5 minutes\] and RTO of
  \[30 minutes or less\]; DR drills at least twice a year with
  documented results. \|

| NFR-05 \| Scalability \| Stateless services, container-based,
  auto-scaling, tested to 3x expected peak. \|

| NFR-06 \| Data integrity \| No loss or duplication of financial
  messages; guaranteed delivery with persistent queues. \|

| NFR-07 \| Resilience \| Failure of any single external party must not
  degrade other integrations (bulkheads, circuit breakers). \|

| NFR-08 \| Maintainability \| Modular, documented codebase; code
  coverage of at least \[80%\] for business logic; static analysis
  gates. \|

| NFR-09 \| Portability \| Deployable on our private cloud, on-premises
  or approved public cloud using containers (Kubernetes) without
  vendor-specific services. \|

| NFR-10 \| Localisation \| Bangla and English in user-facing messages;
  Bangladesh time zone and currency (BDT) handling. \|

# 8. Security and compliance requirements

SEC-01 Compliance with applicable Bangladesh Bank regulations and
guidelines for mobile financial services, ICT security and data
protection, as well as any BTRC and national law requirements in force.
Vendor must state its approach and prior experience.

SEC-02 PCI DSS-aligned design for any component touching card data
(CyberSource, bank card payments); card data must be tokenised or kept
out of scope wherever possible.

SEC-03 Alignment with ISO/IEC 27001 controls; vendor to hold, or be
working towards, ISO 27001 certification.

SEC-04 Encryption in transit (TLS 1.2 or higher, mTLS where supported)
and at rest; key management via HSM or equivalent KMS controlled by us.

SEC-05 Secrets never stored in code or images; integration with a
secrets vault.

SEC-06 Secure SDLC: threat modelling, SAST, DAST, dependency and
container scanning in the pipeline; critical findings block release.

SEC-07 Independent penetration test and vulnerability assessment before
go-live and after major releases, with remediation at vendor cost for
defects in delivered code.

SEC-08 Network segmentation consistent with the DMZ, Application, Data
and Operation zones; no direct external access to the Application or
Data zones.

SEC-09 Role-based access control, least privilege, MFA for all
administrative access, full privileged-access logging.

SEC-10 Data masking of MSISDN, NID and account numbers in logs; no
sensitive data in non-production environments unless masked.

SEC-11 Incident response: vendor to notify us of any security incident
within \[24\] hours; support forensic investigation.

SEC-12 Right for us (and our regulators) to audit the vendor's
development practices relevant to this project.

# 9. Environment control and tooling (mandatory deliverables)

We require a complete, documented toolchain, owned by us, that lets our
team control all environments (Dev, SIT, UAT, Pre-production,
Production, DR).

| ID \| Tool / capability \| Requirement \|

| --- \| --- \| --- \|

| ENV-01 \| Source control and branching \| Git repositories in our
  organisation with a documented branching and release strategy. \|

| ENV-02 \| CI/CD pipelines \| Fully automated build, test, scan,
  package and deploy pipelines; one-click promotion between environments
  with approval gates; rollback in minutes. \|

| ENV-03 \| Infrastructure as Code \| Terraform/Ansible/Helm (or
  equivalent open tools) for all infrastructure and cluster
  configuration; environments can be recreated from code. \|

| ENV-04 \| Containerisation and orchestration \| Docker/OCI images and
  Kubernetes manifests/Helm charts; image registry under our control. \|

| ENV-05 \| Configuration management \| Externalised, versioned
  configuration per environment, with change history and approval. \|

| ENV-06 \| Secrets and certificate management \| Vault (or equivalent)
  integration, automated certificate rotation and expiry alerts. \|

| ENV-07 \| Observability \| Centralised logging, metrics, distributed
  tracing and dashboards (e.g. ELK/OpenSearch, Prometheus, Grafana,
  OpenTelemetry); correlation ID across all hops. \|

| ENV-08 \| Alerting and on-call \| Alert rules, thresholds and runbooks
  for each integration; integration with our incident tooling. \|

| ENV-09 \| Business monitoring \| Real-time dashboards for success
  rate, volume, latency and failures per channel, partner and biller. \|

| ENV-10 \| Test environments and simulators \| Mock/simulator for every
  external integration in Section 5, usable in CI and by our QA team,
  delivered as source. \|

| ENV-11 \| Automated testing \| Unit, integration, contract,
  regression, security and performance test suites runnable by us. \|

| ENV-12 \| Test data management \| Tools to generate and refresh masked
  test data. \|

| ENV-13 \| Backup and restore \| Automated backup, tested restore
  procedures and DR failover scripts. \|

| ENV-14 \| Environment access control \| Identity-integrated access
  (SSO/MFA), bastion/jump hosts and session recording. \|

| ENV-15 \| Release and change management \| Release notes, version
  tagging, change calendar and a documented change process aligned with
  our ITIL practices. \|

| ENV-16 \| Cost and capacity tooling \| Capacity dashboards and scaling
  guidance. \|

| ENV-17 \| Runbooks and documentation \| Installation, operations,
  troubleshooting, DR and upgrade runbooks kept in our repositories. \|

# 10. Technical architecture expectations

Vendors should propose, and justify, an architecture consistent with the
following:

Microservices or modular design running on containers and Kubernetes,
vendor-neutral.

Asynchronous messaging backbone (e.g. Kafka or RabbitMQ) for resilience
and event streaming.

API gateway and integration framework preferably open source (e.g. Kong,
APISIX, WSO2, Apache Camel, Spring Integration or equivalent); vendors
must explain the rationale and long-term support model for each choice.

Mainstream, widely supported languages and frameworks (e.g. Java/Spring,
Go, .NET, Node.js) so that we can hire or train for them.

Integration with the existing zones: NSLB and SFTP in the DMZ,
microservices in the Application Zone, DB in the Data Zone.

Clear separation between the integration layer and the core wallet and
payment logic.

# 11. Delivery requirements

## 11.1 Methodology and governance

Agile delivery with fortnightly demos, a named project manager and
architect, weekly status reporting, risk and issue log, and a joint
steering committee.

Our architects and security team have review rights on designs and code
at all times.

## 11.2 Indicative phases

| Phase \| Content \|

| --- \| --- \|

| 1. Discovery and design \| Requirements validation, solution
  architecture, security design, integration specifications, project
  plan \|

| 2. Foundation \| Environments, CI/CD, IaC, gateway, observability,
  core framework \|

| 3. Integration build \| Connectors by priority group (suggested:
  wallet core and channels, then telco top-up, banks and add money, bill
  and utility payment, remittance, ATM, BanglaQR, AML and data
  warehouse) \|

| 4. Testing \| SIT, UAT, performance, security, DR, regulatory and
  partner certification \|

| 5. Go-live \| Pilot, phased roll-out, hypercare \|

| 6. Handover \| Training, knowledge transfer, documentation sign-off \|

Vendors should propose their own timeline. Our target go-live is
\[Date\].

## 11.3 Acceptance

Payment milestones are tied to accepted deliverables: working software
in our repositories, passing automated tests, completed documentation
and demonstrated deployment by our own team.

## 11.4 Handover package

Complete source code and commit history

Build and deployment pipelines and IaC

API specifications (OpenAPI/WSDL), data models and message formats

Architecture, design and decision records

SBOM and licence inventory

Test suites, simulators and test data tools

Runbooks, operating procedures and DR plan

Admin and user guides

Training sessions (administrators, developers, operations, support) with
recordings

A "clean build" demonstration: our team builds and deploys from scratch
with the vendor observing only

## 11.5 Team and knowledge transfer

Named key personnel; changes require our approval.

Our engineers are embedded in the vendor team (pairing) from Phase 2
onward.

Minimum \[3\] months of post-go-live hypercare with knowledge transfer.

# 12. Warranty, support and maintenance

Warranty: at least \[12\] months after go-live; defects and security
vulnerabilities in delivered code fixed free of charge.

Optional support: 24x7 for Severity 1 and 2 incidents; response and
resolution times to be proposed (e.g. Sev 1: respond in 15 minutes,
restore in 2 hours).

Support must be optional and replaceable: our ownership of code and
tooling means we may switch to another provider or in-house team at any
time without penalty or loss of access.

Service credits for missed SLAs.

Commitment to patch critical vulnerabilities within \[7\] days of
disclosure.

# 13. Commercial requirements

One-time fixed price (or capped time and materials) for design, build,
testing and handover, broken down by phase and by integration.

No recurring licence fee for the delivered middleware. Any third-party
commercial component must be itemised with one-time and recurring costs
and an alternative.

Separate, optional pricing for support and maintenance (annual), and for
each additional integration or change request (rate card).

Infrastructure sizing and estimated hosting cost (we expect to provide
hosting; vendor to specify bill of materials).

Payment tied to milestones; a retention of \[10--15\]% held until the
end of warranty.

Currency, taxes and payment terms as per Annex C.

Termination for convenience, step-in rights and transition assistance at
no extra charge.

# 14. Vendor qualifications

Proposals must include:

Company profile, financial statements (last \[3\] years) and ownership
structure.

At least \[3\] relevant references of mobile money, payments, banking or
telecom middleware projects, with contactable clients; experience in
Bangladesh or South Asia is preferred.

Evidence of experience with the integration types in Section 5
(SMPP/USSD, ISO 20022, SWIFT MT, SOAP/REST with banks and telcos).

CVs of key personnel and proposed team structure, including local
presence.

Certifications (ISO 27001, ISO 9001, CMMI, PCI DSS where relevant).

Statement of any litigation, security incidents or IP disputes in the
past \[5\] years.

Subcontractors, if any, and their roles. Subcontracting requires our
approval and the same ownership terms.

# 15. Proposal format and evaluation

## 15.1 Required proposal contents

Executive summary

Understanding of requirements and assumptions

Proposed solution architecture and technology stack, with
open-source/commercial classification and licences

Compliance matrix against Sections 4 to 12 (Comply / Partially / Not
comply, with comments)

Ownership and IP statement, including any pre-existing IP and exceptions

Environment tooling description (Section 9)

Delivery plan, timeline, team and governance

Testing and quality approach

Security approach

Handover and knowledge-transfer plan

Support and maintenance model

Commercial proposal and pricing schedule

References and qualifications

Risks and dependencies on us

## 15.2 Evaluation criteria (indicative)

| Criterion \| Weight \|

| --- \| --- \|

| Ownership, IP and control compliance (Section 4): pass/fail gate \|
  Gate \|

| Technical solution and architecture \| 25% \|

| Environment tooling and operability (Section 9) \| 15% \|

| Relevant experience and references \| 15% \|

| Security and compliance \| 10% \|

| Delivery approach, team and timeline \| 10% \|

| Commercial (total cost of ownership) \| 20% \|

| Support and handover \| 5% \|

Shortlisted vendors may be asked to present, run a proof of concept
(e.g. one USSD flow and one REST biller integration, built and deployed
in our environment) and be subject to reference checks.

## 15.3 Timeline

| Milestone \| Date \|

| --- \| --- \|

| RFP issued \| \[Date\] \|

| Clarification questions due \| \[Date\] \|

| Proposals due \| \[Date\] \|

| Presentations / PoC \| \[Date\] \|

| Vendor selection \| \[Date\] \|

| Contract signature \| \[Date\] \|

## 15.4 General conditions

We may amend, cancel or reissue this RFP at any time. Costs of preparing
proposals are borne by vendors. All information in this document and its
annexes is confidential and covered by the NDA signed before receipt.
Proposals remain valid for \[90\] days.

# Annex A: Architecture diagram

# Annex B: Assumptions and open points to confirm before issuing

Scope boundary: who delivers the Digital Wallet, Digital Payment,
Digital View and Digital Foundary components, and through which APIs
does the middleware connect to them?

Volumes: expected users, daily transactions, peak TPS, and growth over 3
to 5 years (needed for sizing and pricing).

Hosting: on-premises, private cloud or public cloud, and whether we
provide the Kubernetes platform or the vendor must.

Existing tools: any enterprise standards for CI/CD, monitoring, IAM,
secrets and ITSM that the vendor must adopt.

Regulator: specific Bangladesh Bank and other approvals, audits or
certifications required before launch.

Integration readiness: which external parties (OCS, banks, billers, UCB,
BanglaQR, EC) already have API documentation and sandboxes available,
and who owns relationships.

Push notification server: is the App Server part of this scope or
supplied by the app vendor?

Target go-live date and budget range.

Legal: governing law, IP assignment wording, liability caps and dispute
resolution (to be reviewed by our legal counsel).

# Annex C: Commercial and pricing template

\[Vendor to complete: price per phase, per integration, per environment
tool; optional support; rate card; third-party licences; hosting bill of
materials.\]

# Annex D: Compliance matrix template

| Requirement ID \| Comply (Y / Partial / N) \| Vendor comment \|
  Reference in proposal \|

| --- \| --- \| --- \| --- \|

| OWN-01 to OWN-11 \| \| \| \|

| FR-01 to FR-22 \| \| \| \|

| NFR-01 to NFR-10 \| \| \| \|

| SEC-01 to SEC-12 \| \| \| \|

| ENV-01 to ENV-17 \| \| \| \|
