//! START OF FILE frontend/utils/api.ts
/**
 * API client for NoSlop Backend
 */

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8000';

export interface ProjectRequest {
    title: string;
    project_type: string;
    description: string;
    duration?: number;
    style?: string;
    reference_media?: string[];
}

export interface Project {
    id: string;
    title: string;
    project_type: string;
    description: string;
    status: string;
    created_at: string;
    updated_at: string;
    duration?: number;
    style?: string;
    reference_media: string[];
    metadata: Record<string, any>;
}

export interface Task {
    id: string;
    project_id: string;
    task_type: string;
    description: string;
    status: string;
    assigned_to?: string;
    dependencies: string[];
    priority: number;
    progress: number;
    result?: Record<string, any>;
    created_at: string;
    updated_at: string;
}

export interface TaskProgress {
    task_id: string;
    status: string;
    progress: number;
    message?: string;
    result?: Record<string, any>;
}

class ApiClient {
    private baseUrl: string;

    constructor(baseUrl: string = API_BASE_URL) {
        this.baseUrl = baseUrl;
    }

    private async request<T>(
        endpoint: string,
        options: RequestInit = {}
    ): Promise<T> {
        const url = `${this.baseUrl}${endpoint}`;
        const response = await fetch(url, {
            ...options,
            headers: {
                'Content-Type': 'application/json',
                ...options.headers,
            },
        });

        if (!response.ok) {
            const error = await response.text();
            throw new Error(`API Error: ${response.status} - ${error}`);
        }

        return response.json();
    }

    // Project APIs
    async createProject(data: ProjectRequest): Promise<Project> {
        return this.request<Project>('/api/projects', {
            method: 'POST',
            body: JSON.stringify(data),
        });
    }

    async getProject(id: string): Promise<Project> {
        return this.request<Project>(`/api/projects/${id}`);
    }

    async listProjects(skip: number = 0, limit: number = 100): Promise<Project[]> {
        return this.request<Project[]>(`/api/projects?skip=${skip}&limit=${limit}`);
    }

    async executeProject(id: string): Promise<any> {
        return this.request(`/api/projects/${id}/execute`, {
            method: 'POST',
        });
    }

    // Task APIs
    async getProjectTasks(projectId: string): Promise<Task[]> {
        return this.request<Task[]>(`/api/projects/${projectId}/tasks`);
    }

    async getTaskProgress(taskId: string): Promise<TaskProgress> {
        return this.request<TaskProgress>(`/api/tasks/${taskId}/progress`);
    }

    async executeTask(taskId: string): Promise<any> {
        return this.request(`/api/tasks/${taskId}/execute`, {
            method: 'POST',
        });
    }

    async executeTaskWithDependencies(taskId: string): Promise<any> {
        return this.request(`/api/tasks/${taskId}/execute-with-dependencies`, {
            method: 'POST',
        });
    }

    // Worker APIs
    async listWorkers(): Promise<any[]> {
        return this.request<any[]>('/api/workers');
    }

    async getWorkerCapabilities(workerType: string): Promise<any> {
        return this.request(`/api/workers/${workerType}/capabilities`);
    }
}

export const api = new ApiClient();
export default api;
