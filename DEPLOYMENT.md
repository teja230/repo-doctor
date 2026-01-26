# DirectRunner Implementation - Summary

## ✅ What Was Done

### 1. Created ApplicationRunner Interface

**Location**: `backend/src/main/java/dev/repodoctor/service/ApplicationRunner.java`

### 2. Implemented DirectRunner
**No Docker required** - runs build commands directly on the host system.

**Location**: `backend/src/main/java/dev/repodoctor/service/DirectRunner.java`

**How it works**:
```java
// Instead of: docker run --rm repodoctor-runner:latest mvn test
// It does:    mvn test (directly in workspace directory)
```

### 3. Updated DockerRunner
Implements the same `ApplicationRunner` interface for consistency.

### 4. Added Configuration
**Mode Selection**: Set `RUNNER_MODE` environment variable:
- `docker` (default): Sandboxed execution
- `direct`: For Render/Railway deployment

**Configuration file**: `application.properties`
```properties
repodoctor.runner-mode=${RUNNER_MODE:docker}
```

### 5. Created RunnerConfig
Spring bean that selects the runner based on configuration.

---

## 🚀 How to Deploy to Render

### 1. Update Environment Variables
In Render dashboard, set:
```
RUNNER_MODE=direct
GEMINI_API_KEY=your_key
```

### 2. Deploy
```bash
git push
# Render auto-deploys
```

### 3. That's It!
Your app runs on Render:
- Frontend: `https://repodoctor.onrender.com`
- Backend: `https://repodoctor-api.onrender.com`

---

## ⚙️ Mode Comparison

| Mode       | Use Case                           | Requires      | Security        |
|------------|------------------------------------|---------------|-----------------|
| **docker** | Local dev, self-hosted             | Docker socket | ✅ Sandboxed     |
| **direct** | Render, Railway, managed platforms | Nothing       | ⚠️ No isolation |

---

## 📁 Files Changed

1. ✅ `ApplicationRunner.java` - Interface (renamed from TestRunner)
2. ✅ `DirectRunner.java` - New implementation  
3. ✅ `DockerRunner.java` - Updated to implement ApplicationRunner
4. ✅ `RunnerConfig.java` - Bean factory
5. ✅ `RepoDoctorConfig.java` - Added `runnerMode` field
6. ✅ `Orchestrator.java` - Uses ApplicationRunner interface
7. ✅ `application.properties` - Added runner mode config
8. ✅ `.env.example` - Documented RUNNER_MODE

---

## 🎯 Next Steps

Choose your deployment:

| Option                         | Effort | Cost           | URL                    |
|--------------------------------|--------|----------------|------------------------|
| **Render** (direct mode)       | Low    | Free 750hrs/mo | `*.onrender.com`       |
| **Oracle Cloud** (docker mode) | Medium | Free forever   | IP + Cloudflare Tunnel |

---

## 🚀 Render Deployment Configuration

### Backend Service

| Field               | Value                  |
|---------------------|------------------------|
| **Service Type**    | Web Service            |
| **Name**            | `repodoctor-backend`   |
| **Runtime**         | Docker                 |
| **Branch**          | `main`                 |
| **Root Directory**  | *(leave empty)*        |
| **Dockerfile Path** | `./backend/Dockerfile` |
| **Instance Type**   | Free                   |

**Environment Variables:**
```
RUNNER_MODE=direct
GEMINI_API_KEY=<your-gemini-api-key>
```

**After deployment, copy the backend URL** (e.g., `https://repodoctor-backend.onrender.com`)

---

### Frontend Service

| Field               | Value                   |
|---------------------|-------------------------|
| **Service Type**    | Web Service             |
| **Name**            | `repodoctor-frontend`   |
| **Runtime**         | Docker                  |
| **Branch**          | `main`                  |
| **Root Directory**  | *(leave empty)*         |
| **Dockerfile Path** | `./frontend/Dockerfile` |
| **Instance Type**   | Free                    |

**Environment Variables:**
```
NEXT_PUBLIC_API_URL=https://repodoctor-backend.onrender.com
```

**Important:** Replace `repodoctor-backend.onrender.com` with your actual backend URL from above!

---

## 🔧 Fixes Applied

### Issue 1: "Publish directory dist does not exist"
- **Cause**: Frontend was configured as Static Site instead of Web Service
- **Fix**: Use Docker runtime with `./frontend/Dockerfile`

### Issue 2: "/pom.xml: not found" (Backend)
- **Cause**: Docker build context was repo root, but Dockerfile expected files in current directory
- **Fix**: Updated `COPY` commands to reference `backend/` directory

### Issue 3: "package.json not found" (Frontend)  
- **Cause**: Same as backend - build context mismatch
- **Fix**: Updated `COPY` commands to reference `frontend/` directory

### Issue 4: "Going to http://localhost:8080"
- **Cause**: API URL was hardcoded to localhost
- **Fix**: Made it configurable via `NEXT_PUBLIC_API_URL` environment variable

---

Ready to deploy!
