<!-- # START OF FILE helperfiles/1_THE_IDEA.md -->
# NoSlop Initial Idea

Self hosted media making and sharing decentralized social network.

The goal is to create a framework that is easy to deploy on available consumer devices, easy to create, edit and share media.

## NoSlop Modules
- NoSlop Seed - Smart installer to deploy NoSlop on locally available consumer devices
- NoSlop Media Creator - Local Admin AI driven media making cluster
- NoSlop Blockchain - Decentralised media credential registry
- NoSlop Social Network - Decentralised social network of private NoSlop nodes

## NoSlop Architecture

The NoSlop Seed smart installer automatically deploys an AI driven media making cluster utilising ALL your available consumer hardware resources. This way the NoSlop Network stays completelly decentralised and free for humanity to create good quality content and share them with each other.

Users can create, edit and share media with others in a safe and private environment. NoSlop combines the power of local LLMs, local TTV and TTI solutions via ComfyUI and local automated video and image editing via FFMPEG and OpenCV. A local first mesh network where the created media is kept locally untill it is requested by other authorized users on other nodes. The system is entirelly under the controll of the user via the local Admin AI agent (ollama) that assists the user in creating any media they want.

**Advanced AI Editing**: The Admin AI is not just a generator; it is a professional-grade video and photo editor. It can ingest existing user footage and photos, apply color grading, perform complex edits, and compile them into industry-standard films. Whether mixing generated content with real-world footage or polishing a vlog, the Admin AI ensures top-tier quality.

Users can record videos and take photos via connected devices as well as generate media via local comfyUI API using community and LLM generated workflows.

Minimum Recommended Local Resources: Multi core CPU, 6Gb GPU, 16Gb RAM, 1 Tb HDD

## The NoSlop flow:

1. The Smart installer deploys NoSlop (ollama, comfyui, ffmpeg, openCV, etc.) on locally available consumer devices (ssh access necessary).
2. The user registers on local NoSlop creates a profile and customises the local Admin AI's personality.
3. NoSlop's Admin AI helps the user get started by starting the Admin AI assisted NoSlop Creator Workflow:
    1. At this stage the user can use old fashioned methods to create media (record video, take photos, etc.)
    2. The user can also use NoSlop's Admin AI to generate media via local comfyUI API using community and LLM generated workflows.
    3. The Admin AI asks the user what type of media they want to create (cinematic film, corporate video, advertisement, comedy skit, cartoon, vlog, podcast, etc.).
    4. NoSlop's backend creates a dedicated project at wich point a dedicated Project Manager (PM) agent is created to manage the project:
        1. create a project plan and initial project setup tasks based on the user's request and initial choices
        2. create specialised worker agents to handle specific tasks
        3. assign setup tasks to worker agents
        4. monitor progress & report project start to the Admin AI
    5. Depending on the type of media being created the Admin AI takes the User through the process like setting up a scene, characters, props, working on the storyboard, etc. through an audio visual, conversational process with the option of providing reference photos or videos at any point.
    6. The Admin AI then monitors the progress and assists the PM if it needs any assistance likewise the PM assists the worker agents if they need any assistance. The worker agents carry out the tasks assigned to them using tools provided by NoSlop.
    7. The goal of the NoSlop Creator Workflow is to provide three various outcomes from which the user will pick one and then decides whether to accept that as the final media OR to generate new three versions based on the choice and the optional user feedback.
    8. The NoSlop Creator iterates this process until the user is satisfied with the final media.
4. Once the user is satisfied with the final media it is stored locally and based on the user's preferences it is made available to other authorized nodes.
5. Likewise the Admin AI of the local NoSlop install will pro actively identify and download media from other authorized nodes this way creating the same high availability for the user without the need to store media on remote servers like YouTube or TikTok etc.
6. All connected users of the NoSlop network can interact with one another and each other's media, like, share and comment on it, and all the other usual social networking features.
    - **Ad-Free & Cost-Free**: The decentralized P2P nature of the network eliminates the need for servers, ads, or subscription fees.
    - **Guardian LLM**: A dedicated local AI agent protects the user and the network from harmful content.
    - **Community Moderation**: A democratic up/down voting system helps the Guardian LLM identify and filter unsafe content.
    - **User-Controlled Feed**: The algorithm that populates the user's feed is entirely under their control. Users can adjust parameters to see exactly what they want.
7. All media that is shared to the NoSlop Network is recorded on the NoSlop Blockchain in order to provide a tamper proof and decentralized media sharing environment.


## Software Stack

### Backend

- Ollama
- ComfyUI
- FFMPEG
- OpenCV
- Python

### Frontend

- Web UI
- Mobile App

### Database

- SQLite
- PostgreSQL

### Storage

- Local Storage
- Local Mesh Network