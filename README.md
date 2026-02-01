# RepoDoctor 🩺

![Architecture](https://img.shields.io/badge/Stack-Next.js%20%2B%20Spring%20Boot-blue)
![AI](https://img.shields.io/badge/AI-Gemini%203-purple)
![License](https://img.shields.io/badge/License-MIT-green)

**Autonomous Build-Fixing Agent powered by Gemini 3**

RepoDoctor automatically diagnoses and fixes failing tests through a closed loop: **diagnose → patch → run → repeat**.

Built for Gemini 3 Hackathon: https://devpost.com/software/repo-doctor-drzun2

## Try it out

- This is live at: https://repodoctor.onrender.com/
- You can use the following repos for testing:
  - https://github.com/teja230/failing-maven-project
  - https://github.com/teja230/mercanto-ai
  - https://github.com/vinayanand3/dividends-alert

## 🚀 Quick Start

### Prerequisites
- Docker & Docker Compose
- Java 21 (for local backend development)
- Node.js 20 (for local frontend development)

### Run with Docker Compose

```bash
# Set your Gemini API key (optional - works without for baseline runs)
export GEMINI_API_KEY=your-api-key-here

# Start all services
docker-compose up

# Open http://localhost:3000
```

### Run for Development

```bash
# Terminal 1: Build runner image
cd runner && docker build -t repodoctor-runner:latest .

# Terminal 2: Start backend
cd backend && ./mvnw spring-boot:run

# Terminal 3: Start frontend
cd frontend && npm install && npm run dev
```

## 📖 How It Works

1. **Submit Repository**: Upload a ZIP file or provide a GitHub URL
2. **Baseline Run (Attempt 0)**: Run tests in a sandboxed Docker container
3. **Diagnose**: Gemini 3 Flash analyzes failure logs with `thinkingLevel="MINIMAL"` for fast log parsing
4. **Patch**: Gemini 3 Flash proposes a fix with `thinkingLevel="LOW"` for balanced reasoning
5. **Apply & Test**: Apply the unified diff and rerun tests
6. **Repeat**: Continue until tests pass or max attempts reached

## 🔒 Security Model

| Protection            | Implementation                             |
|-----------------------|--------------------------------------------|
| **Sandbox**           | All code runs in Docker with non-root user |
| **Resource Limits**   | 1 CPU, 2GB RAM, 5 min timeout              |
| **Network Isolation** | `--network none` option available          |
| **File Limits**       | Max 25MB ZIP, 250 files                    |
| **Path Validation**   | Rejects patches modifying outside workspace |

## 🤖 How We Used Gemini 3

> This section documents Gemini 3 features for hackathon judging.

### A) Thinking Control

Gemini 3 introduces `thinkingLevel` control (MINIMAL, LOW, MEDIUM, HIGH) to balance speed vs. reasoning depth.

| Task             | Model                    | `thinkingLevel` | Why                                                             |
|------------------|--------------------------|-----------------|-----------------------------------------------------------------|
| Log Analysis     | `gemini-3-flash-preview` | `MINIMAL`       | Fast structured extraction from logs - minimal reasoning needed |
| Patch Generation | `gemini-3-flash-preview` | `LOW`           | Balanced speed and reasoning for code fixes                     |

**Note:** We use `LOW` for patch generation rather than `HIGH` to optimize for fast iteration. The multi-turn history (see below) compensates by building context across attempts.

### B) Structured Outputs

All Gemini responses use strict JSON schemas:

```json
{
  "unified_diff": "string",
  "explanation": "string",
  "confidence_notes": "string",
  "touched_files": ["string"],
  "risk_level": "LOW|MEDIUM|HIGH"
}
```

Invalid JSON triggers `LLM_INVALID_OUTPUT` status - no hallucinated patches applied.

### C) Multi-Turn History (Thought Signatures)

We preserve conversation history per job, enabling:
- Reference to prior failed attempts
- Avoiding repeated unsuccessful patches
- Building on previous reasoning context

Each Gemini 3 response includes a `thoughtSignature` field that captures the model's internal reasoning state across turns.

### D) Implementation Details

**API Integration:**
- Using Gemini API v1beta (`generativelanguage.googleapis.com/v1beta`)
- Model: `gemini-3-flash-preview` (verified via API models endpoint)
- Direct REST API calls via Spring WebClient (reactive, non-blocking)
- 120-second timeout for complex patch generation
- Automatic retry with exponential backoff on rate limits (429)

**Request Configuration:**
```json
{
  "thinkingConfig": {"thinkingLevel": "LOW/MINIMAL"},
  "responseMimeType": "application/json",
  "responseSchema": {},
  "maxOutputTokens": 65536,
  "temperature": 0.2
}
```

See [GeminiClient.java](backend/src/main/java/dev/repodoctor/llm/GeminiClient.java) for the full implementation.

## 🔗 GitHub Integration (PR Creation)

RepoDoctor can automatically create Pull Requests on GitHub with your fixes. After a successful fix, click the **"🚀 Create Pull Request"** button to:

1. Authenticate with GitHub OAuth
2. Create a new branch (`repodoctor/fix-{jobId}-{attempt}`)
3. Apply the patch via GitHub API
4. Open a PR with auto-generated title and description

### Setup GitHub Integration

1. **Create a GitHub OAuth App** at https://github.com/settings/developers
2. Configure the app:
   - **Application name**: `RepoDoctor`
   - **Homepage URL**: `http://localhost:3000` (or your frontend URL)
   - **Authorization callback URL**: `http://localhost:8080/api/github/callback`
3. Copy the **Client ID** and generate a **Client Secret**
4. Add to your `.env`:
   ```bash
   GITHUB_PR_CREATION_ENABLED=true
   GITHUB_CLIENT_ID=your_client_id_here
   GITHUB_CLIENT_SECRET=your_client_secret_here
   ```

### Auto-Generated PR Content

The PR description includes:
- **Summary**: What was fixed and test improvement metrics
- **Problem**: Original failure analysis from baseline
- **Solution**: Gemini's explanation of the fix
- **Test Results**: Before/after comparison table
- **Risk Assessment**: LOW/MEDIUM/HIGH with confidence notes
- **Files Changed**: List of modified files
- **Attempt History**: If multiple attempts were needed

Example PR title: `fix: Correct null check in UserService [RepoDoctor]`

## 📡 API Reference

| Endpoint                              | Method | Description                                                                   |
|---------------------------------------|--------|-------------------------------------------------------------------------------|
| `/api/jobs`                           | POST   | Create job (multipart: `repoUrl` OR `repoZip`, `maxAttempts`, `allowNetwork`) |
| `/api/jobs/{id}`                      | GET    | Get job metadata                                                              |
| `/api/jobs/{id}/events`               | GET    | SSE stream for real-time updates                                              |
| `/api/jobs/{id}/attempts`             | GET    | List all attempts                                                             |
| `/api/jobs/{id}/attempts/{k}/diff`    | GET    | Get patch diff                                                                |
| `/api/jobs/{id}/attempts/{k}/logs`    | GET    | Get build logs                                                                |
| `/api/jobs/{id}/attempts/{k}/summary` | GET    | Get JSON summary                                                              |
| `/api/github/status`                  | GET    | Check if GitHub integration is enabled                                        |
| `/api/github/authorize`               | GET    | Start OAuth flow (returns auth URL)                                           |
| `/api/github/callback`                | GET    | OAuth callback handler (creates PR, redirects to GitHub)                      |

### SSE Events

- `job_started` - Job processing began
- `attempt_started` - Attempt N began (baseline or fix)
- `run_completed` - Test run finished
- `patch_proposed` - Gemini proposed a patch
- `patch_applied` - Patch was applied to repo
- `attempt_completed` - Attempt finished
- `job_completed` - All done
- `error` - Something went wrong

## 📁 Project Structure

```
repo-doctor/
├── backend/           # Spring Boot API
│   └── src/main/java/dev/repodoctor/
│       ├── controller/    # REST endpoints (Job, GitHub)
│       ├── service/       # Core logic (Orchestrator, GitHubService, PullRequestGenerator)
│       ├── config/        # Configuration classes (GitHubConfig, RunnerConfig)
│       ├── llm/           # Gemini integration
│       └── model/         # JPA entities
├── frontend/          # Next.js UI
│   └── src/app/
│       ├── page.tsx       # Home (submit form)
│       ├── jobs/[jobId]/  # Job details + PR button
│       └── error/         # OAuth error page
├── runner/            # Docker image (Maven, Gradle, Node)
├── samples/           # Demo projects
└── docker-compose.yml
```

## 🧪 Demo

Use the included sample project:

```bash
# Create a ZIP of the failing Maven project
cd samples/failing-maven-project
zip -r ../failing-demo.zip .

# Upload via UI or API
curl -X POST -F "repoZip=@samples/failing-demo.zip" \
  http://localhost:8080/api/jobs
```

The sample has a `Calculator.add()` bug that returns subtraction instead of addition. RepoDoctor will:
1. Detect 2 failing tests in baseline
2. Analyze the assertion errors
3. Propose a fix changing `a - b` to `a + b`
4. Apply the patch and verify all tests pass

## ⚙️ Configuration

### Environment Variables

| Variable                     | Default                                       | Description                                                                                |
|------------------------------|-----------------------------------------------|--------------------------------------------------------------------------------------------|
| `GEMINI_API_KEY`             | -                                             | **Required** for AI fixes. Get from [Google AI Studio](https://aistudio.google.com/apikey) |
| `CORS_ALLOWED_ORIGINS`       | `http://localhost:3000,http://localhost:8080` | Comma-separated list of allowed frontend origins                                           |
| `H2_CONSOLE_ENABLED`         | `false`                                       | Enable H2 database console (only for debugging)                                            |
| `ARTIFACTS_PATH`             | `./artifacts`                                 | Storage for logs/diffs                                                                     |
| `WORKSPACES_PATH`            | `./workspaces`                                | Cloned repo storage                                                                        |
| `GITHUB_PR_CREATION_ENABLED` | `false`                                       | Enable "Create PR" button (requires OAuth setup)                                           |
| `GITHUB_CLIENT_ID`           | -                                             | GitHub OAuth App Client ID                                                                 |
| `GITHUB_CLIENT_SECRET`       | -                                             | GitHub OAuth App Client Secret                                                             |
| `GITHUB_CALLBACK_URL`        | `http://localhost:8080/api/github/callback`   | OAuth callback URL (must match GitHub app config)                                          |
| `GITHUB_FRONTEND_URL`        | `http://localhost:3000`                       | Frontend URL for redirects after OAuth                                                     |

### Deployment to Production

Copy `.env.example` to `.env` and configure for your environment:

**Render:**
```bash
CORS_ALLOWED_ORIGINS=https://your-frontend.onrender.com,https://your-backend.onrender.com
GEMINI_API_KEY=your_key_here
H2_CONSOLE_ENABLED=false
```

**Google Cloud Run:**
```bash
CORS_ALLOWED_ORIGINS=https://your-frontend-hash.run.app,https://your-backend-hash.run.app
GEMINI_API_KEY=your_key_here
```

**Vercel + Railway:**
```bash
CORS_ALLOWED_ORIGINS=https://your-app.vercel.app,https://your-api.up.railway.app
GEMINI_API_KEY=your_key_here
```

**GitHub PR Creation (any platform):**
```bash
# Remember to update your GitHub OAuth App's callback URL to match!
GITHUB_PR_CREATION_ENABLED=true
GITHUB_CLIENT_ID=your_client_id
GITHUB_CLIENT_SECRET=your_client_secret
GITHUB_CALLBACK_URL=https://your-backend-domain.com/api/github/callback
GITHUB_FRONTEND_URL=https://your-frontend-domain.com
```

### Application Properties

See [application.properties](backend/src/main/resources/application.properties) for all options.

## 🛡️ Security Notes

### For Hackathon Demo
- H2 console disabled by default
- CORS restricted to localhost by default
- Docker socket mounted (⚠️ allows container escape - see below)

### For Production Deployment
**Critical:** The current implementation mounts Docker socket (`/var/run/docker.sock`) which allows container escape. For production:

1. **Option A**: Use [tecnativa/docker-socket-proxy](https://github.com/Tecnativa/docker-socket-proxy) to restrict Docker API access
2. **Option B**: Use rootless Docker
3. **Option C**: Deploy to serverless platforms that don't require Docker socket access

**Additional Recommendations:**
- Set `H2_CONSOLE_ENABLED=false` (already default)
- Use proper authentication/authorization (not included in MVP)
- Implement rate limiting on API endpoints
- Restrict `CORS_ALLOWED_ORIGINS` to your actual frontend domain
- Monitor resource usage (containers can consume significant CPU/RAM)

## 📜 License

MIT License - See [LICENSE](LICENSE) for details.

---

Built with ❤️ for the Gemini 3 Hackathon
