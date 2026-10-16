# AdminOS

> Your personal life admin agent — watches your bank, email, and SMS so you don't have to.

AdminOS is an open-source, India-first personal finance agent. It watches bank transactions (via on-device SMS parsing), Gmail, and uploaded bank statement PDFs, then proactively surfaces actionable insights: unused subscriptions, upcoming bills, anomalies, and weekly briefings.

## Quick Start

```bash
git clone https://github.com/adminos/adminos
cd adminos
cp .env.example .env               # add your Google OAuth client (and Claude API key)
docker compose --profile full up --build
```

Open `http://localhost:3000`. The web app proxies `/api/*` to the API (`localhost:8080`); all ports are bound to localhost only.

- `docker compose up`: Postgres, Redis and the API only (no workers or web).
- The API runs the database migrations on startup; workers and web start once it's healthy.

## Architecture

```
Next.js Web App → Kotlin+Ktor API → Redis Queue → Go Workers → PostgreSQL
                                                 → Python PDF Parser
                                                 → Claude API (Agent)
```

- **API Server**: Kotlin + Ktor (modular monolith)
- **Workers**: Go (Gmail ingestion, SMS processing, Agent Runner)
- **PDF Parser**: Python + FastAPI (pdfplumber)
- **Database**: PostgreSQL 18 (20 tables, 6 domains; Flyway migrations in `services/api/src/main/resources/db/migration`)
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
├── docker-compose.yml        # Full stack in one command
├── .env.example              # Required environment variables
└── README.md
```

## Development

### Prerequisites

- Docker + Docker Compose (enough to run everything)
- For local development: JDK 25 (or let Gradle download it), Node.js 24, Python 3.14. Go isn't needed locally; see `CLAUDE.md` for the Docker command.

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
