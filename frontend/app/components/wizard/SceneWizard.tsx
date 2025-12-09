'use client';

import { useState, useEffect } from 'react';
import api, { ProjectRequest, Project } from '../../../utils/api';
import BasicInfo from './steps/BasicInfo';
import StyleGateway from './steps/StyleGateway';
import Characters from './steps/Characters';
import Review from './steps/Review';

interface SceneWizardProps {
    onSuccess: (project: any) => void;
    onCancel: () => void;
    existingProject?: Project | null;
}

export type WizardData = {
    title: string;
    project_type: string;
    description: string;
    duration: number;
    style: string;
    characters: any[];
    reference_media: string[];
};

const INITIAL_DATA: WizardData = {
    title: '',
    project_type: 'cinematic_film',
    description: '',
    duration: 60,
    style: '',
    characters: [],
    reference_media: []
};

export default function SceneWizard({ onSuccess, onCancel, existingProject = null }: SceneWizardProps) {
    const [step, setStep] = useState(1);
    const [data, setData] = useState<WizardData>(INITIAL_DATA);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const isEditMode = existingProject !== null;

    useEffect(() => {
        if (isEditMode && existingProject) {
            setData({
                title: existingProject.title,
                project_type: existingProject.project_type,
                description: existingProject.description,
                duration: existingProject.duration || 60,
                style: existingProject.style || '',
                characters: existingProject.metadata?.characters || [],
                reference_media: existingProject.reference_media || []
            });
        }
    }, [isEditMode, existingProject]);

    const updateData = (updates: Partial<WizardData>) => {
        setData(prev => ({ ...prev, ...updates }));
    };

    const nextStep = () => setStep(prev => prev + 1);
    const prevStep = () => setStep(prev => prev - 1);

    const handleSubmit = async () => {
        setLoading(true);
        setError(null);
        try {
            const projectRequest: ProjectRequest = {
                title: data.title,
                project_type: data.project_type,
                description: data.description,
                duration: data.duration,
                style: data.style,
                reference_media: data.reference_media,
            };

            if (isEditMode && existingProject) {
                // We are editing, so we just pass the updated data to the onSuccess handler
                onSuccess({ ...existingProject, ...projectRequest });
            } else {
                // We are creating, so we call the API
                const project = await api.createProject(projectRequest);
                onSuccess(project);
            }
        } catch (err: any) {
            setError(err.message || (isEditMode ? 'Failed to update project' : 'Failed to create project'));
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="bg-white dark:bg-zinc-900 rounded-lg shadow-xl max-w-4xl w-full max-h-[90vh] flex flex-col">
            {/* Header */}
            <div className="p-6 border-b border-zinc-200 dark:border-zinc-800 flex justify-between items-center">
                <div>
                    <h2 className="text-2xl font-bold text-zinc-900 dark:text-zinc-100">
                        {isEditMode ? 'Edit Project' : 'New Project Setup'}
                    </h2>
                    <p className="text-sm text-zinc-500 dark:text-zinc-400">
                        Step {step} of 4
                    </p>
                </div>
                <button
                    onClick={onCancel}
                    className="text-zinc-500 hover:text-zinc-700 dark:text-zinc-400 dark:hover:text-zinc-200"
                >
                    ✕
                </button>
            </div>

            {/* Progress Bar */}
            <div className="w-full bg-zinc-200 dark:bg-zinc-800 h-1">
                <div
                    className="bg-blue-600 h-1 transition-all duration-300 ease-in-out"
                    style={{ width: `${(step / 4) * 100}%` }}
                />
            </div>

            {/* Content */}
            <div className="flex-1 overflow-y-auto p-6">
                {error && (
                    <div className="mb-6 p-4 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg text-red-600 dark:text-red-400">
                        {error}
                    </div>
                )}

                {step === 1 && (
                    <BasicInfo data={data} updateData={updateData} />
                )}
                {step === 2 && (
                    <StyleGateway data={data} updateData={updateData} />
                )}
                {step === 3 && (
                    <Characters data={data} updateData={updateData} />
                )}
                {step === 4 && (
                    <Review data={data} />
                )}
            </div>

            {/* Footer / Controls */}
            <div className="p-6 border-t border-zinc-200 dark:border-zinc-800 flex justify-between">
                <button
                    onClick={prevStep}
                    disabled={step === 1}
                    className={`px-6 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 
                        ${step === 1
                            ? 'opacity-50 cursor-not-allowed text-zinc-400'
                            : 'text-zinc-700 dark:text-zinc-300 hover:bg-zinc-50 dark:hover:bg-zinc-800'}`}
                >
                    Back
                </button>

                {step < 4 ? (
                    <button
                        onClick={nextStep}
                        className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
                    >
                        Next
                    </button>
                ) : (
                    <button
                        onClick={handleSubmit}
                        disabled={loading}
                        className="px-6 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700 transition-colors disabled:opacity-50"
                    >
                        {loading
                            ? isEditMode ? 'Updating Project...' : 'Creating Project...'
                            : isEditMode ? 'Update Project' : 'Create Project'}
                    </button>
                )}
            </div>
        </div>
    );
}
