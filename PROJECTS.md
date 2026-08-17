# Projects

This is the index I use to keep the names and purpose of my projects consistent.

Some of them started with very temporary names while I was experimenting. I keep the old names in comments at the bottom of this file so I can still recognize older folders, notes and conversations without bringing that mess into the public-facing names.

<!--
Maintenance note:
This file is intentionally more practical than the profile README.
The README is for people visiting the profile; this one is also for me when I come back to a project after a while.
-->

## 🧊 Forge3D Studio

**Repository name:** `forge3d-studio`  
**Area:** 3D tools / Roblox / asset pipelines

A desktop toolkit for preparing 3D content for Roblox-oriented workflows.

The project is meant to handle more than a single model format or one rigid use case. The idea is to be able to inspect different kinds of assets separately — characters, armor, weapons, wings, props and other models — and later assemble or prepare them for use in a game pipeline.

Things this project is concerned with:

- importing and inspecting 3D assets
- model and texture conversion
- character/equipment assembly
- animation-related workflows
- game-ready asset preparation
- Roblox compatibility
- keeping imported asset types independent instead of assuming everything has a rig

<!--
Do not reduce Forge3D to "a Roblox model converter".
The broader goal is a small 3D production environment/toolkit.
-->

---

## ⚡ ProAim

**Repository name:** `proaim`  
**Area:** Windows / gaming performance / input latency

A Windows utility focused on understanding and tuning the parts of the system that affect how games and input feel.

Main areas:

- mouse and USB-related configuration
- display and refresh-rate checks
- latency diagnostics
- system-performance checks
- gaming-oriented configuration
- making applied changes understandable and reversible where possible

I don't want this project to behave like the usual “one click = +500 FPS” optimizer. If something is changed, the user should have a reasonable idea of what happened.

<!--
Old working names for this project were very generic, especially "PC optimizer" and "optimización mouse y pantalla".
Use ProAim publicly from now on.
-->

---

## 🎓 UTN Scholar

**Repository name:** `utn-scholar`  
**Area:** Education / documents / AI-assisted study

A study tool centered on university material.

The main idea is to take notes, PDFs, slides and other course material and make them easier to navigate and study from instead of leaving everything scattered across folders and chats.

Planned/ongoing areas include:

- document organization
- course-specific workspaces
- summaries and explanations
- guided study
- question generation
- keeping context between study sessions
- making source material easy to return to

<!--
The original mental model was "NotebookLM for UTN".
That's useful internally, but UTN Scholar is the project name.
-->

---

## 📱 A53 Performance

**Repository name:** `a53-performance`  
**Area:** Android / device utilities

An Android maintenance utility that originally grew out of things I wanted on my Samsung Galaxy A53.

It covers practical device-management tasks such as storage cleanup, scanning, performance-related controls and showing what happened after an action instead of making the user rescan everything unnecessarily.

Important product ideas:

- deleted items should disappear immediately from the UI
- free-space information should update as actions happen
- cleanup actions should explain what they actually do
- long operations shouldn't make the interface feel frozen
- avoid fake or misleading “RAM boost” behavior

<!--
The phone model explains where the project started; the code may become more general over time.
Don't rename it every time device support expands.
-->

---

## 🗃️ WA Vault

**Repository name:** `wa-vault`  
**Area:** Android / local data / event reliability

An Android project for preserving and organizing message-related events and media locally.

A lot of the interesting work is not in the basic UI but in handling timing and ordering correctly when Android delivers things late, applications restart or multiple media items belong to the same event.

Areas I've worked on include:

- message/event capture
- media association
- delayed-file handling
- FIFO/batch processing
- duplicate protection
- restart-safe pending work
- documents, images, video and audio handling

<!--
Public descriptions should stay focused on local reliability and data handling.
Don't describe it as breaking into, spying on or bypassing another app.
-->

---

## 🌦️ Meteora Weather

**Repository name:** `meteora-weather`  
**Area:** Weather / data presentation

A weather application built around a simple idea: show the information that matters without making the user fight the interface.

Areas of interest:

- current conditions
- forecasts
- local weather information
- readable presentation
- useful detail without visual overload

<!-- Meteora is the canonical name even if older notes simply say "servicio meteorológico". -->

---

## Repository checklist

When one of these projects gets cleaned up for public viewing, I want it to have most of the following:

- [ ] a short README that explains why the project exists
- [ ] screenshots that match the current version
- [ ] build/install instructions that actually work
- [ ] current version or release information
- [ ] a short tech-stack section
- [ ] known limitations when they matter
- [ ] a small roadmap only if there are concrete next steps
- [ ] a license when appropriate
- [ ] sensible commit messages
- [ ] no temporary APKs, random ZIP names or old exports cluttering the root

<!--
This checklist is for maintenance, not decoration.
It's fine if an early project doesn't tick every box yet.
-->

## Naming rules I want to keep

- Product names use normal capitalization: `Forge3D Studio`, `UTN Scholar`, etc.
- Repository names stay lowercase and simple.
- Don't put version numbers in repository names.
- Don't create a new repository just because an app moved from v2.6.2 to v2.6.3.
- Old names remain aliases, not new project identities.

<!--
====================================================================
INTERNAL ALIAS MAP
Keep this section. It lets old chats, filenames and working names map back
to the current project without exposing all those temporary names in the
main profile README.
====================================================================

Meteora Weather
- servicio meteorológico
- app meteorológica
- weather app

UTN Scholar
- NotebookLM para la UTN
- APPS UTN V2
- app UTN
- notebook UTN

Forge3D Studio
- generador 3D para Roblox
- Forge3D
- Forge3D Studio
- compatibilidad Forge3D y Roblox

ProAim
- app para optimizar mi PC
- optimización mouse y pantalla
- PC optimizer
- ProAim Optimizer
- optimizador PC

A53 Performance
- app para mi Galaxy A53
- APP - GALAXY
- Galaxy app
- A53Performance
- app Galaxy

WA Vault
- app para recuperar mensajes
- WSP V2
- recuperar mensajes
- WhatsApp recovery app

Canonical repository slugs
- meteora-weather
- utn-scholar
- forge3d-studio
- proaim
- a53-performance
- wa-vault
-->
