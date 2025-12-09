//! START OF FILE frontend/app/components/ProjectDetail.tsx
'use client';

import { useState, useEffect } from 'react';
import api, { Project, Task, ProjectRequest } from '../../utils/api';
import SceneWizard from './wizard/SceneWizard';

interface ProjectDetailProps {
    projectId: string;
    onClose?: () => void;
    onProjectUpdate: () => void;
}

export default function ProjectDetail({ projectId, onClose, onProjectUpdate }: ProjectDetailProps) {
    const [project, setProject] = useState<Project | null>(null);
    const [tasks, setTasks] = useState<Task[]>([]);
    const [loading, setLoading] = useState(true);
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [isEditModalOpen, setIsEditModalOpen] = useState(false);

    useEffect(() => {
        loadProjectDetails();
    }, [projectId]);

    const loadProjectDetails = async () => {
        try {
            setLoading(true);
            const projectData = await api.getProject(projectId);
            const tasksData = await api.getProjectTasks(projectId);
            setProject(projectData);
            setTasks(tasksData);
            setError(null);
        } catch (err: any) {
            setError(err.message || 'Failed to load project details');
        } finally {
            setLoading(false);
        }
    };

    const handleStart = async () => {
        setIsSubmitting(true);
        try {
            await api.startProject(projectId);
            await loadProjectDetails();
        } catch (err: any) {
            setError(err.message || 'Failed to start project');
        } finally {
            setIsSubmitting(false);
        }
    };

    const handlePause = async () => {
        setIsSubmitting(true);
        try {
            await api.pauseProject(projectId);
            await loadProjectDetails();
        } catch (err: any) {
            setError(err.message || 'Failed to pause project');
        } finally {
            setIsSubmitting(false);
        }
    };

    const handleStop = async () => {
        setIsSubmitting(true);
        try {
            await api.stopProject(projectId);
            await loadProjectDetails();
        } catch (err: any) {
            setError(err.message || 'Failed to stop project');
        } finally {
            setIsSubmitting(false);
        }
    };

    const handleEdit = () => {
        setIsEditModalOpen(true);
    };

    const handleDelete = async () => {
        if (window.confirm('Are you sure you want to delete this project?')) {
            setIsSubmitting(true);
            try {
                await api.deleteProject(projectId);
                onProjectUpdate(); // Refresh project list
                if (onClose) onClose();
            } catch (err: any) {
                setError(err.message || 'Failed to delete project');
            } finally {
                setIsSubmitting(false);
            }
        }
    };

    const handleUpdateProject = async (updatedData: ProjectRequest) => {
        setIsSubmitting(true);
        try {
            await api.updateProject(projectId, updatedData);
            setIsEditModalOpen(false);
            await loadProjectDetails();
            onProjectUpdate(); // Refresh project list
        } catch (err: any) {
            setError(err.message || 'Failed to update project');
        } finally {
            setIsSubmitting(false);
        }
    };

    const getStatusColor = (status: string) => {
        switch (status) {
            case 'completed':
                return 'bg-green-100 text-green-800 dark:bg-green-900/20 dark:text-green-400';
            case 'in_progress':
                return 'bg-blue-100 text-blue-800 dark:bg-blue-900/20 dark:text-blue-400';
            case 'pending':
            case 'planning':
                return 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/20 dark:text-yellow-400';
            case 'failed':
                return 'bg-red-100 text-red-800 dark:bg-red-900/20 dark:text-red-400';
            case 'paused':
                return 'bg-gray-100 text-gray-800 dark:bg-gray-900/20 dark:text-gray-400';
            case 'stopped':
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

    if (error || !project) {
        return (
            <div className="p-4 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg">
                <p className="text-sm text-red-600 dark:text-red-400">{error || 'Project not found'}</p>
            </div>
        );
    }

    return (
        <div className="space-y-6">
            {/* Header */}
            <div className="flex items-start justify-between">
                <div className="flex-1">
                    <h2 className="text-2xl font-bold text-zinc-900 dark:text-zinc-100">
                        {project.title}
                    </h2>
                    <p className="text-sm text-zinc-600 dark:text-zinc-400 mt-1">
                        {project.description}
                    </p>
                </div>
                {onClose && (
                    <button
                        onClick={onClose}
                        className="text-zinc-500 hover:text-zinc-700 dark:hover:text-zinc-300"
                    >
                        ✕
                    </button>
                )}
            </div>

            {/* Metadata */}
            <div className="grid grid-cols-2 gap-4">
                <div>
                    <span className="text-sm text-zinc-600 dark:text-zinc-400">Status:</span>
                    <span className={`ml-2 px-2 py-1 rounded text-xs font-medium ${getStatusColor(project.status)}`}>
                        {project.status}
                    </span>
                </div>
                <div>
                    <span className="text-sm text-zinc-600 dark:text-zinc-400">Type:</span>
                    <span className="ml-2 text-sm text-zinc-900 dark:text-zinc-100">
                        {(project.project_type || '').replace('_', ' ')}
                    </span>
                </div>
                {project.duration && (
                    <div>
                        <span className="text-sm text-zinc-600 dark:text-zinc-400">Duration:</span>
                        <span className="ml-2 text-sm text-zinc-900 dark:text-zinc-100">
                            {project.duration}s
                        </span>
                    </div>
                )}
                {project.style && (
                    <div>
                        <span className="text-sm text-zinc-600 dark:text-zinc-400">Style:</span>
                        <span className="ml-2 text-sm text-zinc-900 dark:text-zinc-100">
                            {project.style}
                        </span>
                    </div>
                )}
            </div>

            {/* Project Controls */}
            <div className="flex space-x-2">
                <button
                    onClick={handleStart}
                    disabled={project.status === 'in_progress' || project.status === 'completed'}
                    className="px-4 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    Start
                </button>
                <button
                    onClick={handlePause}
                    disabled={project.status !== 'in_progress'}
                    className="px-4 py-2 bg-yellow-600 text-white rounded-lg hover:bg-yellow-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    Pause
                </button>
                <button
                    onClick={handleStop}
                    disabled={project.status !== 'in_progress' && project.status !== 'paused'}
                    className="px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    Stop
                </button>
                <button
                    onClick={handleEdit}
                    disabled={project.status === 'in_progress'}
                    className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    Edit
                </button>
                <button
                    onClick={handleDelete}
                    className="px-4 py-2 bg-gray-600 text-white rounded-lg hover:bg-gray-700 transition-colors"
                >
                    Delete
                </button>
            </div>

            {isEditModalOpen && (
                <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
                    <div className="bg-white dark:bg-zinc-800 p-8 rounded-lg shadow-xl w-full max-w-2xl">
                        <h2 className="text-2xl font-bold mb-4">Edit Project</h2>
                        <SceneWizard
                            initialData={project}
                            onSuccess={(updatedProject) => {
                                handleUpdateProject(updatedProject);
                                setIsEditModalOpen(false);
                            }}
                            onCancel={() => setIsEditModalOpen(false)}
                        />
                        <button
                            onClick={() => setIsEditModalOpen(false)}
                            className="mt-4 text-sm text-zinc-600 dark:text-zinc-400 hover:underline"
                        >
                            Cancel
                        </button>
                    </div>
                </div>
            )}

            {/* Tasks */}
            <div>
                <h3 className="text-lg font-semibold text-zinc-900 dark:text-zinc-100 mb-3">
                    Tasks ({tasks.length})
                </h3>
                {tasks.length === 0 ? (
                    <p className="text-sm text-zinc-600 dark:text-zinc-400">No tasks yet</p>
                ) : (
                    <div className="space-y-2">
                        {tasks.map((task) => (
                            <div
                                key={task.id}
                                className="p-3 bg-white dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-800 rounded-lg"
                            >
                                <div className="flex items-start justify-between">
                                    <div className="flex-1">
                                        <div className="flex items-center space-x-2">
                                            <span className="text-sm font-medium text-zinc-900 dark:text-zinc-100">
                                                {(task.task_type || '').replace('_', ' ')}
                                            </span>
                                            <span className={`px-2 py-0.5 rounded text-xs font-medium ${getStatusColor(task.status)}`}>
                                                {task.status}
                                            </span>
                                        </div>
                                        <p className="text-xs text-zinc-600 dark:text-zinc-400 mt-1">
                                            {task.description}
                                        </p>
                                        {task.progress > 0 && (
                                            <div className="mt-2">
                                                <div className="w-full bg-zinc-200 dark:bg-zinc-700 rounded-full h-1.5">
                                                    <div
                                                        className="bg-blue-600 h-1.5 rounded-full transition-all"
                                                        style={{ width: `${task.progress}%` }}
                                                    ></div>
                                                </div>
                                                <span className="text-xs text-zinc-500 mt-1">{task.progress}%</span>
                                            </div>
                                        )}
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                )}
            </div>
        </div>
    );
}
