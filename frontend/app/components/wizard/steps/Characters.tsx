'use client';

import { WizardData } from '../SceneWizard';
import { useState } from 'react';

interface CharactersProps {
    data: WizardData;
    updateData: (updates: Partial<WizardData>) => void;
}

export default function Characters({ data, updateData }: CharactersProps) {
    const [name, setName] = useState('');
    const [desc, setDesc] = useState('');

    const addCharacter = () => {
        if (name.trim()) {
            const newChar = { name: name.trim(), description: desc.trim() };
            updateData({ characters: [...data.characters, newChar] });
            setName('');
            setDesc('');
        }
    };

    const removeCharacter = (index: number) => {
        const newChars = [...data.characters];
        newChars.splice(index, 1);
        updateData({ characters: newChars });
    };

    return (
        <div className="space-y-6 animate-fadeIn">
            <div>
                <h3 className="text-xl font-medium text-zinc-900 dark:text-zinc-100 mb-4">
                    Characters
                </h3>
                <p className="text-zinc-600 dark:text-zinc-400 mb-6">
                    Who is in this scene? Define the key players.
                </p>
            </div>

            <div className="bg-zinc-50 dark:bg-zinc-800/50 p-4 rounded-lg border border-zinc-200 dark:border-zinc-700 mb-6">
                <h4 className="font-medium text-sm text-zinc-900 dark:text-zinc-100 mb-3">Add New Character</h4>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-4">
                    <div>
                        <label className="block text-xs font-medium text-zinc-500 mb-1">Name</label>
                        <input
                            type="text"
                            value={name}
                            onChange={(e) => setName(e.target.value)}
                            className="w-full px-3 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-800 text-sm"
                            placeholder="e.g. John Doe"
                        />
                    </div>
                </div>
                <div className="mb-4">
                    <label className="block text-xs font-medium text-zinc-500 mb-1">Description (Appearance, Role)</label>
                    <textarea
                        value={desc}
                        onChange={(e) => setDesc(e.target.value)}
                        rows={2}
                        className="w-full px-3 py-2 border border-zinc-300 dark:border-zinc-700 rounded-lg bg-white dark:bg-zinc-800 text-sm"
                        placeholder="e.g. A grumpy detective in his 50s, wears a trench coat."
                    />
                </div>
                <button
                    onClick={addCharacter}
                    disabled={!name.trim()}
                    className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    Add Character
                </button>
            </div>

            <div className="space-y-3">
                <h4 className="font-medium text-sm text-zinc-900 dark:text-zinc-100">Cast List</h4>
                {data.characters.length === 0 && (
                    <p className="text-sm text-zinc-500 italic">No characters added yet.</p>
                )}
                {data.characters.map((char, idx) => (
                    <div key={idx} className="flex items-start justify-between p-4 bg-white dark:bg-zinc-800 rounded-lg border border-zinc-200 dark:border-zinc-700 shadow-sm">
                        <div>
                            <div className="font-medium text-zinc-900 dark:text-zinc-100">{char.name}</div>
                            {char.description && (
                                <p className="text-sm text-zinc-600 dark:text-zinc-400 mt-1">{char.description}</p>
                            )}
                        </div>
                        <button
                            onClick={() => removeCharacter(idx)}
                            className="text-red-500 hover:text-red-700 text-sm ml-4"
                        >
                            ✕
                        </button>
                    </div>
                ))}
            </div>
        </div>
    );
}
