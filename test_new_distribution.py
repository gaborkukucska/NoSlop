#!/usr/bin/env python3
"""
Test script to demonstrate the new service distribution logic.
Simulates the user's 4-device setup.
"""

from seed.role_assigner import RoleAssigner
from seed.models import DeviceCapabilities, GPUVendor, OSType, Architecture

# Simulate the user's actual hardware
devices = []

# BigBOY - RTX 3060 12GB, 31GB RAM, 16 cores
bigboy = DeviceCapabilities(
    hostname='BigBOY',
    ip_address='192.168.0.22',
    cpu_cores=16,
    cpu_speed_ghz=5.0,
    cpu_architecture=Architecture.X86_64,
    ram_total_gb=31.15,
    ram_available_gb=16.64,
    gpu_vendor=GPUVendor.NVIDIA,
    gpu_name='NVIDIA GeForce RTX 3060',
    vram_total_gb=12.0,
    vram_available_gb=9.78,
    gpu_count=1,
    disk_total_gb=379.34,
    disk_available_gb=114.65,
    os_type=OSType.LINUX,
    os_version='Linux-6.14.0-36-generic-x86_64-with-glibc2.39',
    ssh_available=False,
    ssh_username='tom'
)
devices.append(bigboy)

# tomsbot - RTX 3050 Ti 4GB, 15GB RAM, 20 cores
tomsbot = DeviceCapabilities(
    hostname='tomsbot',
    ip_address='192.168.0.30',
    cpu_cores=20,
    cpu_speed_ghz=0.4,
    cpu_architecture=Architecture.X86_64,
    ram_total_gb=15.32,
    ram_available_gb=11.87,
    gpu_vendor=GPUVendor.NVIDIA,
    gpu_name='NVIDIA GeForce RTX 3050 Ti Laptop GPU',
    vram_total_gb=4.0,
    vram_available_gb=4.0,
    gpu_count=1,
    disk_total_gb=320.0,
    disk_available_gb=43.0,
    os_type=OSType.LINUX,
    os_version='Ubuntu 24.04.3 LTS',
    ssh_available=True,
    ssh_username='tomanderson'
)
devices.append(tomsbot)

# 2014 - No GPU, 15GB RAM, 8 cores
device_2014 = DeviceCapabilities(
    hostname='2014',
    ip_address='192.168.0.24',
    cpu_cores=8,
    cpu_speed_ghz=2.26,
    cpu_architecture=Architecture.X86_64,
    ram_total_gb=15.53,
    ram_available_gb=14.14,
    gpu_vendor=GPUVendor.NONE,
    vram_total_gb=0.0,
    vram_available_gb=0.0,
    gpu_count=0,
    disk_total_gb=229.0,
    disk_available_gb=177.0,
    os_type=OSType.LINUX,
    os_version='Ubuntu 24.04.3 LTS',
    ssh_available=True,
    ssh_username='tom'
)
devices.append(device_2014)

# lenovo - No GPU, 5.67GB RAM, 2 cores
lenovo = DeviceCapabilities(
    hostname='lenovo',
    ip_address='192.168.0.15',
    cpu_cores=2,
    cpu_speed_ghz=0.88,
    cpu_architecture=Architecture.X86_64,
    ram_total_gb=5.67,
    ram_available_gb=3.88,
    gpu_vendor=GPUVendor.NONE,
    vram_total_gb=0.0,
    vram_available_gb=0.0,
    gpu_count=0,
    disk_total_gb=117.0,
    disk_available_gb=102.0,
    os_type=OSType.LINUX,
    os_version='Ubuntu 24.04.3 LTS',
    ssh_available=True,
    ssh_username='tomanderson'
)
devices.append(lenovo)

# Create deployment plan
assigner = RoleAssigner()
print('\n' + '='*80)
print('NEW SERVICE DISTRIBUTION - Optimized for Hardware Utilization')
print('='*80)
plan = assigner.create_deployment_plan(devices)
assigner.print_deployment_plan(plan)

# Print comparison
print('\n' + '='*80)
print('BEFORE vs AFTER Comparison')
print('='*80)

print('\n📊 Service Counts:')
print(f'  Frontend:  4 → {sum(1 for node in plan.nodes if "noslop-frontend" in node.services)}')
print(f'  Ollama:    1 → {sum(1 for node in plan.nodes if "ollama" in node.services)}')
print(f'  ComfyUI:   1 → {sum(1 for node in plan.nodes if "comfyui" in node.services)}')
print(f'  FFmpeg:    1 → {sum(1 for node in plan.nodes if "ffmpeg" in node.services)}')
print(f'  OpenCV:    1 → {sum(1 for node in plan.nodes if "opencv" in node.services)}')

print('\n🎯 Benefits:')
print('  ✅ 3x Ollama instances for distributed LLM inference')
print('  ✅ 2x ComfyUI instances for parallel image generation')
print('  ✅ 3x FFmpeg instances for distributed video processing')
print('  ✅ Only 1 frontend (saves resources, accessible via master IP)')
print('  ✅ Service redundancy (survives individual node failures)')
print('  ✅ tomsbot\'s GPU now utilized (was wasted before)')
print('  ✅ 2014\'s CPU/RAM now utilized (was wasted before)')

print('\n' + '='*80)
