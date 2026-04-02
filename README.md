# AdminOS

> Your personal life admin agent — watches your bank, email, and SMS so you don't have to.

AdminOS is an open-source, India-first personal finance agent. It watches bank transactions (via on-device SMS parsing), Gmail, and uploaded bank statement PDFs, then proactively surfaces actionable insights: unused subscriptions, upcoming bills, anomalies, and weekly briefings.

## Quick Start

```bash
git clone https://github.com/adminos/adminos
cd adminos
cp .env.example .env   # Add your Google OAuth + Claude API key
docker compose up
```

The API server runs on `http://localhost:8080`. The web app runs on `http://localhost:3000`.

## Architecture

```
Next.js Web App → Kotlin+Ktor API → Redis Queue → Go Workers → PostgreSQL+TimescaleDB
                                                 → Python PDF Parser
                                                 → Claude API (Agent)
```

- **API Server**: Kotlin + Ktor (modular monolith)
- **Workers**: Go (Gmail ingestion, SMS processing, Agent Runner)
- **PDF Parser**: Python + FastAPI (pdfplumber)
- **Database**: PostgreSQL + TimescaleDB (20 tables, 6 domains)
- **Queue**: Redis + Asynq
- **Frontend**: Next.js + TypeScript + Tailwind

## Project Structure

```
adminos/
├── apps/
│   └── web/                  # Next.js web application
├── services/
│   ├── api/                  # Kotlin + Ktor API server
│   ├── workers/              # Go worker pool
│   └── pdf-parser/           # Python PDF parser
├── infra/
│   └── migrations/           # PostgreSQL migration files
├── docker-compose.yml        # Full stack in one command
├── .env.example              # Required environment variables
└── README.md
```

## Development

### Prerequisites

- JDK 21+
- Go 1.21+
- Python 3.11+
- Node.js 20+
- Docker + Docker Compose

### Run API server locally

```bash
cd services/api
./gradlew run
```

### Run tests

```bash
cd services/api
./gradlew test
```

## Privacy

- Raw SMS text never leaves the device — only structured JSON is transmitted
- All data is stored in your own database
- Self-hostable with one Docker Compose command
- No third-party analytics or tracking in the core product

## License

MIT
