# Moneyflow — Docker Fundamentals (Applied)

**What this document is:** the core Docker concepts you need to work with this project end to end, explained through the actual files already sitting in this repo rather than generic examples. If a Docker term ever feels fuzzy again, this is the doc to come back to before re-deriving it from scratch.

---

## 1. The problem Docker actually solves

`package.json` (or `pom.xml`) captures your *dependencies* — the libraries your code needs. It does not capture the OS, the exact runtime version, or any system-level library your dependencies quietly rely on underneath. "Works on my machine" almost always means one of those unstated layers differs between machines.

Docker's answer: package the entire environment — OS layer, runtime, libraries, and your code — into one portable unit that behaves identically anywhere it runs. Same instinct as dependency management, one full layer deeper.

---

## 2. The four kinds of things, and how they relate

| Term | What it actually is | Closest thing you already know |
|---|---|---|
| **Image** | An inert, read-only blueprint — a snapshot of a filesystem plus run instructions. Built once, reused many times. | A compiled build artifact — your Angular `dist/` bundle, or a `.jar` file. Not running, just sitting there. |
| **Container** | A live, running instance of an image. Many containers can run from one image, each with its own writable layer on top of the same shared image underneath. | That `dist/` bundle actually being served in a browser tab, or `java -jar app.jar` actually executing. |
| **Volume** | Docker-managed persistent storage, decoupled from any single container's lifecycle. Survives container destruction; only removed by an explicit delete. | A mounted network drive — the compute using it can be swapped out without losing what's stored on it. |
| **Registry** | A place to store and share images (GHCR, Docker Hub). | The npm registry — `docker push`/`docker pull` are `npm publish`/`npm install`, one layer below application code. |

The one habit worth building: **containers are disposable by default.** Docker assumes a container can be destroyed and recreated at any moment with zero consequence — that's a deliberate design philosophy, not a limitation. Anything that must survive a container being torn down (a database file, an issued TLS certificate) has to be deliberately placed in a volume. If it isn't, it's gone the next time that container restarts.

---

## 3. The Dockerfile — the recipe that produces an image

This project's `DockerFile` is a real, working example of the standard shape:

- **`FROM`** — the base image to start from (this project uses two: a JDK image to build, a slimmer JRE image to actually run — a *multi-stage build*, so the final image never carries the compiler/build tools, only what's needed to run).
- **`WORKDIR`** — sets the working directory inside the image for subsequent instructions, same idea as `cd`-ing before running commands.
- **`COPY`** — brings files from your machine (or CI runner) into the image. Order matters: this project copies `pom.xml` and the Maven wrapper *before* the source code specifically so Docker's layer cache can reuse the "download all dependencies" step across builds — that layer only gets invalidated when dependencies actually change, not on every code change.
- **`RUN`** — executes a command *while building* the image (e.g. compiling the app). This only ever happens at build time, never at container startup.
- **`ENTRYPOINT`** — the command that runs when a *container* starts from this image. This is the build/runtime boundary made explicit in one file: everything above `ENTRYPOINT` happens once, during `docker build`; `ENTRYPOINT` itself happens every time, at `docker run`.

`docker build` reads a Dockerfile top to bottom and produces one image. Nothing executes your actual application at this stage — you're compiling the blueprint, not running it.

---

## 4. Registries — GHCR is npm, one layer down

Once an image is built, it needs somewhere to live so other machines (like the Lightsail VM) can fetch it without rebuilding from source every time. That's what GHCR is — `ghcr.io/nishk02/moneyflow-api` is exactly analogous to a scoped npm package name. CI's `docker push` after a successful build/scan is `npm publish`. The VM's `docker compose pull` is `npm install` for that image. Whether that package is public or private works exactly like npm's public/private scoping too — which is the actual GHCR task still sitting on the backlog.

---

## 5. `docker run` — the atomic unit, by hand

Before Compose enters the picture, a single container can be started manually with `docker run <image> <command>` plus flags for volumes (`-v`), environment variables (`-e` or `--env-file`), and ports (`-p`). You've actually already done this directly — the Litestream restore drill was exactly this: one manual `docker run` invocation with explicit flags, no Compose file involved. Every flag in that command has a direct, named counterpart in `docker-compose.yml` — same mechanism, two different interfaces.

---

## 6. `docker-compose.yml` — not magic, just batched `docker run` calls

This is the honest, de-mystifying way to read Compose: **everything in the file is a structured way of writing out multiple `docker run` invocations, plus the setup work (a shared network, named volumes) that would otherwise be tedious flags typed by hand.**

Reading this project's `docker-compose.yml` with that lens:

```yaml
services:
  api:
    image: ghcr.io/nishk02/moneyflow-api:latest   # which image to run
    expose:
      - "8080"                                     # reachable by other containers, not the host
    environment:
      SPRING_PROFILES_ACTIVE: prod                 # env vars, same as `docker run -e`
      JWT_SECRET: ${JWT_SECRET}
      DB_PATH: /app/data/moneyflow.db
    volumes:
      - moneyflow-data:/app/data                    # mount a named volume, same as `docker run -v`

  caddy: ...
  litestream: ...

volumes:
  moneyflow-data:      # declaring this here is the equivalent of `docker volume create moneyflow-data`
  caddy-data:
  caddy-config:
```

- **`services:`** — the list of containers to run. Each entry underneath (`api`, `caddy`, `litestream`) is one container's worth of settings.
- **`environment:`** — the mechanism for injecting config at runtime rather than hardcoding it into the image, the same twelve-factor-app principle already used for `JWT_SECRET` and `API_DOMAIN`: the same image and Compose file can run in different environments purely by changing what's fed into these variables.
- **`volumes:`** (per-service) — which named volumes this container mounts, and where. **`volumes:`** (top-level) — declaring those volumes to exist at all; Compose refuses to start if a service references a volume that isn't declared here (a real bug hit and fixed during this project's Caddy setup).
- **`depends_on:`** — start-up ordering only (start this container after that one) — it does not wait for the other service to be *ready*, just *started*. Worth knowing as a limitation if a future service needs to wait for another to actually be accepting connections.

`docker compose up -d` reads this whole file and performs, in one command, everything you'd otherwise type as a sequence of individual `docker run`/`docker volume create`/`docker network create` commands.

---

## 7. Networking — service names are hostnames

Every service in the same Compose file automatically joins a shared private network, and can reach every other service **by its service name, resolved like a hostname.** This is why `Caddyfile` says `reverse_proxy api:8080` — `api` isn't a placeholder, Compose genuinely makes that name resolvable, scoped to a private network only these containers can see. It's the same idea as public DNS resolving `api.mnyflo.com`, just private and automatic instead of something you configured on a registrar.

---

## 8. The full lifecycle, named end to end

Putting every piece from this project into one line:

**`DockerFile`** (the recipe) → `docker build` in CI produces an **image** → CI `docker push`es that image to **GHCR** (the registry) → **`docker-compose.yml`** declares which images to run, how they're networked, configured via env vars, and given persistent storage → `docker compose pull && docker compose up -d` on the VM fetches the images and starts the **containers**, wiring up the **volumes** and shared **network** declared in the file.

Every piece of infrastructure work in this project's deployment phase was one of these five steps — Caddy and Litestream are simply more entries in the same `services:` list, following the exact same rules as `api` does.

---

## 9. Habits worth keeping as defaults

- **Assume containers are disposable.** If data must survive a restart or redeploy, it needs a named volume — never assume a container's own filesystem is safe.
- **Assume config belongs in environment variables, not the image.** The same image should be able to run in different environments by changing only what's injected at runtime — never by rebuilding it with different hardcoded values.
- **A service reaches another service by name, not IP**, as long as both are declared in the same Compose file.
- **Compose is not a separate technology from Docker** — it's a declarative shorthand for commands you could type by hand, one container at a time, with `docker run`.
