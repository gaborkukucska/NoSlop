'use client';

import { WizardData } from '../SceneWizard';
import { useState } from 'react';

interface StyleGatewayProps {
    data: WizardData;
    updateData: (updates: Partial<WizardData>) => void;
}

export default function StyleGateway({ data, updateData }: StyleGatewayProps) {
    const [newRef, setNewRef] = useState('');

    const handleAddReference = () => {
        if (newRef.trim()) {
            updateData({ reference_media: [...data.reference_media, newRef.trim()] });
            setNewRef('');
        }
    };

    const removeReference = (index: number) => {
        const newRefs = [...data.reference_media];
        newRefs.splice(index, 1);
        updateData({ reference_media: newRefs });
    };

    return (
        <div className="space-y-6 animate-fadeIn">
            <div>
                <h3 className="text-xl font-medium text-zinc-900 dark:text-zinc-100 mb-4">
                    Style & References
                </h3>
                <p className="text-zinc-600 dark:text-zinc-400 mb-6">
                    Define the look and feel of your masterpiece.
                </p>
            </div>

            <div>
                <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-2">
                    Visual Style Prompt
                </label>
                <p className="text-xs text-zinc-500 mb-2">
                    Describe colors, lighting, camera work, and atmosphere. e.g., "Cyberpunk, neon lights, rainy streets, handheld camera, high contrast"
                </p>
                <textarea
                    value={data.style}
                    onChange={(e) => updateData({ style: e.target.value })}
                    rows={3}
                    className="w-full px-4 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-800 text-zinc-900 dark:text-zinc-100 focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                    placeholder="Enter style keywords or description..."
                />
            </div>

            <div className="border-t border-zinc-200 dark:border-zinc-800 pt-6">
                <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-2">
                    Reference Media (URLs)
                </label>
                <div className="flex gap-2 mb-4">
                    <input
                        type="url"
                        value={newRef}
                        onChange={(e) => setNewRef(e.target.value)}
                        placeholder="https://example.com/image.jpg"
                        className="flex-1 px-4 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-800 text-zinc-900 dark:text-zinc-100 focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                        onKeyPress={(e) => e.key === 'Enter' && handleAddReference()}
                    />
                    <button
                        onClick={handleAddReference}
                        className="px-4 py-2 bg-zinc-200 dark:bg-zinc-700 text-zinc-800 dark:text-zinc-200 rounded-lg hover:bg-zinc-300 dark:hover:bg-zinc-600"
                    >
                        Add
                    </button>
                </div>

                {data.reference_media.length > 0 && (
                    <div className="space-y-2">
                        {data.reference_media.map((ref, idx) => (
                            <div key={idx} className="flex items-center justify-between p-3 bg-zinc-50 dark:bg-zinc-800/50 rounded-lg border border-zinc-200 dark:border-zinc-700">
                                <span className="text-sm text-zinc-600 dark:text-zinc-400 truncate flex-1 mr-4">
                                    {ref}
                                </span>
                                <button
                                    onClick={() => removeReference(idx)}
                                    className="text-red-500 hover:text-red-700 text-sm"
                                >
                                    Remove
                                </button>
                            </div>
                        ))}
                    </div>
                )}
                {data.reference_media.length === 0 && (
                    <p className="text-sm text-zinc-500 italic">No reference media added yet.</p>
                )}
            </div>
        </div>
    );
}
