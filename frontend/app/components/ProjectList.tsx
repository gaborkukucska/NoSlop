//! START OF FILE frontend/app/components/ProjectList.tsx
'use client';

import { useState, useEffect } from 'react';
import api, { Project } from '../../utils/api';

interface ProjectListProps {
    onProjectClick?: (project: Project) => void;
    refreshTrigger?: number;
}

export default function ProjectList({ onProjectClick, refreshTrigger }: ProjectListProps) {
    const [projects, setProjects] = useState<Project[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        loadProjects();
    }, [refreshTrigger]);

    const loadProjects = async () => {
        try {
            setLoading(true);
            const data = await api.listProjects();
            setProjects(data);
            setError(null);
        } catch (err: any) {
            setError(err.message || 'Failed to load projects');
        } finally {
            setLoading(false);
        }
    };

    const getStatusColor = (status: string) => {
        switch (status) {
            case 'completed':
                return 'bg-green-100 text-green-800 dark:bg-green-900/20 dark:text-green-400';
            case 'in_progress':
                return 'bg-blue-100 text-blue-800 dark:bg-blue-900/20 dark:text-blue-400';
            case 'planning':
                return 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/20 dark:text-yellow-400';
            case 'paused':
                return 'bg-gray-100 text-gray-800 dark:bg-gray-900/20 dark:text-gray-400';
            case 'cancelled':
                return 'bg-red-100 text-red-800 dark:bg-red-900/20 dark:text-red-400';
            default:
                return 'bg-zinc-100 text-zinc-800 dark:bg-zinc-900/20 dark:text-zinc-400';
        }
    };

    if (loading) {
        return (
            <div className="flex items-center justify-center p-8">
                <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
            </div>
        );
    }

    if (error) {
        return (
            <div className="p-4 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg">
                <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
            </div>
        );
    }

    if (projects.length === 0) {
        return (
            <div className="text-center p-8">
                <p className="text-zinc-600 dark:text-zinc-400">No projects yet. Create your first one!</p>
            </div>
        );
    }

    return (
        <div className="space-y-3">
            {projects.map((project) => (
                <div
                    key={project.id}
                    onClick={() => onProjectClick?.(project)}
                    className="p-4 bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-lg hover:shadow-md transition-shadow cursor-pointer"
                >
                    <div className="flex items-start justify-between">
                        <div className="flex-1">
                            <h3 className="text-lg font-semibold text-zinc-900 dark:text-zinc-100">
                                {project.title}
                            </h3>
                            <p className="text-sm text-zinc-600 dark:text-zinc-400 mt-1 line-clamp-2">
                                {project.description}
                            </p>
                            <div className="flex items-center space-x-3 mt-3">
                                <span className={`px-2 py-1 rounded text-xs font-medium ${getStatusColor(project.status)}`}>
                                    {project.status}
                                </span>
                                <span className="text-xs text-zinc-500">
                                    {project.project_type.replace('_', ' ')}
                                </span>
                                {project.duration && (
                                    <span className="text-xs text-zinc-500">
                                        {project.duration}s
                                    </span>
                                )}
                            </div>
                        </div>
                    </div>
                </div>
            ))}
        </div>
    );
}
