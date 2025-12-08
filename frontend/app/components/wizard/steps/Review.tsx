'use client';

import { WizardData } from '../SceneWizard';

interface ReviewProps {
    data: WizardData;
}

export default function Review({ data }: ReviewProps) {
    return (
        <div className="space-y-6 animate-fadeIn">
            <div>
                <h3 className="text-xl font-medium text-zinc-900 dark:text-zinc-100 mb-4">
                    Review Project Details
                </h3>
                <p className="text-zinc-600 dark:text-zinc-400 mb-6">
                    Almost there! Review your project setup before we hand it over to the Production Manager.
                </p>
            </div>

            <div className="bg-zinc-50 dark:bg-zinc-900/50 rounded-lg border border-zinc-200 dark:border-zinc-800 p-6 space-y-6">
                <div className="grid grid-cols-2 gap-4">
                    <div>
                        <span className="block text-xs font- uppercase tracking-wider text-zinc-500 mb-1">Title</span>
                        <span className="text-lg font-medium text-zinc-900 dark:text-zinc-100">{data.title || 'Untitled'}</span>
                    </div>
                    <div>
                        <span className="block text-xs font- uppercase tracking-wider text-zinc-500 mb-1">Type</span>
                        <span className="inline-block px-2 py-1 bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-300 rounded text-sm font-medium">
                            {data.project_type.replace('_', ' ')}
                        </span>
                    </div>
                    <div>
                        <span className="block text-xs font- uppercase tracking-wider text-zinc-500 mb-1">Duration</span>
                        <span className="text-base text-zinc-900 dark:text-zinc-100">{data.duration} seconds</span>
                    </div>
                </div>

                <div>
                    <span className="block text-xs font- uppercase tracking-wider text-zinc-500 mb-1">Premise</span>
                    <p className="text-zinc-700 dark:text-zinc-300 bg-white dark:bg-zinc-800 p-3 rounded border border-zinc-200 dark:border-zinc-700 text-sm">
                        {data.description || 'No description provided.'}
                    </p>
                </div>

                <div>
                    <span className="block text-xs font- uppercase tracking-wider text-zinc-500 mb-1">Visual Style</span>
                    <p className="text-zinc-700 dark:text-zinc-300 text-sm">
                        {data.style || 'Default style'}
                    </p>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                    <div>
                        <span className="block text-xs font- uppercase tracking-wider text-zinc-500 mb-2">Cast ({data.characters.length})</span>
                        <ul className="text-sm space-y-1 list-disc list-inside text-zinc-600 dark:text-zinc-400">
                            {data.characters.length > 0 ? (
                                data.characters.map((idx, i) => (
                                    <li key={i}>{idx.name}</li>
                                ))
                            ) : (
                                <li className="italic">No characters listed</li>
                            )}
                        </ul>
                    </div>
                    <div>
                        <span className="block text-xs font- uppercase tracking-wider text-zinc-500 mb-2">References ({data.reference_media.length})</span>
                        <ul className="text-sm space-y-1 list-disc list-inside text-zinc-600 dark:text-zinc-400">
                            {data.reference_media.length > 0 ? (
                                data.reference_media.map((ref, i) => (
                                    <li key={i} className="truncate max-w-[200px]">{ref}</li>
                                ))
                            ) : (
                                <li className="italic">No reference media</li>
                            )}
                        </ul>
                    </div>
                </div>
            </div>

            <div className="bg-blue-50 dark:bg-blue-900/20 p-4 rounded-lg flex items-start gap-3">
                <span className="text-xl">🚀</span>
                <div>
                    <h4 className="font-medium text-blue-900 dark:text-blue-100 text-sm">Ready to Create</h4>
                    <p className="text-blue-700 dark:text-blue-300 text-xs mt-1">
                        Clicking "Create Project" will initialize the Project Manager agent, assign tasks, and begin the pre-production workflow.
                    </p>
                </div>
            </div>
        </div>
    );
}
