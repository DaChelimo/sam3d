# SAM3D Desktop Application

## What this project is
Kotlin Multiplatform / Compose Desktop application that wraps the SAM3D-GCODE Python CLI pipeline.
The Python engine lives in a sibling directory at ../SAM3D-GCODE/ — it is never modified.

## Critical rules
1. Never modify any file inside ../SAM3D-GCODE/. It is the research engine.
2. The pipeline is invoked as a CLI subprocess — there is no HTTP/Flask layer.
3. Always read SAM3D_DESKTOP_PLAN.md before starting any new component. It is the single source of truth.
4. commonMain must contain zero java.* APIs. jvmMain is where JVM-specific code lives.

## Project layout
- composeApp/src/commonMain/ — ViewModels, domain model, @Serializable DTOs
- composeApp/src/jvmMain/  — ProcessBuilder, dcm4che, all UI, file I/O
- composeApp/src/jvmTest/resources/fixtures/ — test fixtures

## Python pipeline — key files to read before touching integration code
- ../SAM3D-GCODE/sam3d.py              — CLI entry point
- ../SAM3D-GCODE/reprompting3d.py      — defines points.json write format
- ../SAM3D-GCODE/scale_transform.py    — parse_prompts() reads points.json
- ../SAM3D-GCODE/utils.py              — load3dmatrix and padtocube (must match in Kotlin)

## Commands
./gradlew :composeApp:run         # run the app
./gradlew :composeApp:jvmTest     # run all tests
./gradlew :composeApp:packageDmg  # build macOS distribution

## Key architecture decisions
- Integration: CLI subprocess (sam3d.py), NOT Flask
- Module: single :composeApp, commonMain/jvmMain source sets
- JSON format: single tempdir/points.json, keys "positive"/"negative", [x,y,z] in padded-cube voxel space
- Pixel normalisation: global min-max (matches Python's utils.load3dmatrix) — NOT HU windowing
- Full padded cube (S³ ByteArray) held in memory during the Prompting step
