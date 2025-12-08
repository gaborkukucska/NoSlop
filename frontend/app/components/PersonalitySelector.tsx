//! START OF FILE frontend/app/components/PersonalitySelector.tsx
'use client';

import { useState, useEffect } from 'react';
import api from '../../utils/api';

interface PersonalityProfile {
    type: string;
    creativity: number;
    formality: number;
    verbosity: number;
    enthusiasm: number;
    technical_depth: number;
}

interface PersonalitySelectorProps {
    sessionId?: string;
    onPersonalityChange?: (personality: PersonalityProfile) => void;
}

const presetTypes = ['creative', 'technical', 'balanced'];

const personalityDescriptions = {
    creative: 'Highly creative and enthusiastic, encouraging bold artistic choices',
    technical: 'Technically precise and detail-oriented, focusing on specifications',
    balanced: 'Balances creativity with practicality, offering both artistic and technical guidance',
};

export default function PersonalitySelector({
    sessionId = 'default',
    onPersonalityChange
}: PersonalitySelectorProps) {
    const [selectedPreset, setSelectedPreset] = useState('balanced');
    const [customMode, setCustomMode] = useState(false);
    const [personality, setPersonality] = useState<PersonalityProfile>({
        type: 'balanced',
        creativity: 0.7,
        formality: 0.5,
        verbosity: 0.6,
        enthusiasm: 0.7,
        technical_depth: 0.6,
    });

    useEffect(() => {
        loadPreset(selectedPreset);
    }, []);

    const loadPreset = async (presetType: string) => {
        try {
            const data = await api.getPersonalityPreset(presetType);
            setPersonality(data);
            setSelectedPreset(presetType);
            setCustomMode(false);
        } catch (error) {
            console.error('Error loading preset:', error);
        }
    };

    const applyPersonality = async () => {
        try {
            await api.setPersonalityWithSession(personality, sessionId);
            onPersonalityChange?.(personality);
            alert('Personality updated successfully!');
        } catch (error) {
            console.error('Error applying personality:', error);
            alert('Failed to update personality');
        }
    };

    const handleSliderChange = (key: keyof PersonalityProfile, value: number) => {
        setPersonality((prev) => ({
            ...prev,
            [key]: value,
            type: 'custom',
        }));
        setCustomMode(true);
    };

    return (
        <div className="bg-white dark:bg-zinc-900 rounded-lg shadow-lg p-6">
            <h2 className="text-xl font-semibold text-zinc-900 dark:text-zinc-50 mb-4">
                🎭 Admin AI Personality
            </h2>

            {/* Preset Selection */}
            <div className="mb-6">
                <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-2">
                    Preset Personalities
                </label>
                <div className="grid grid-cols-3 gap-2">
                    {presetTypes.map((preset) => (
                        <button
                            key={preset}
                            onClick={() => loadPreset(preset)}
                            className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${selectedPreset === preset && !customMode
                                ? 'bg-blue-600 text-white'
                                : 'bg-zinc-100 dark:bg-zinc-800 text-zinc-700 dark:text-zinc-300 hover:bg-zinc-200 dark:hover:bg-zinc-700'
                                }`}
                        >
                            {preset.charAt(0).toUpperCase() + preset.slice(1)}
                        </button>
                    ))}
                </div>
                <p className="text-xs text-zinc-600 dark:text-zinc-400 mt-2">
                    {personalityDescriptions[selectedPreset as keyof typeof personalityDescriptions]}
                </p>
            </div>

            {/* Custom Sliders */}
            <div className="space-y-4 mb-6">
                <div>
                    <label className="flex justify-between text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                        <span>🎨 Creativity</span>
                        <span className="text-blue-600">{(personality.creativity * 100).toFixed(0)}%</span>
                    </label>
                    <input
                        type="range"
                        min="0"
                        max="1"
                        step="0.1"
                        value={personality.creativity}
                        onChange={(e) => handleSliderChange('creativity', parseFloat(e.target.value))}
                        className="w-full h-2 bg-zinc-200 dark:bg-zinc-700 rounded-lg appearance-none cursor-pointer accent-blue-600"
                    />
                </div>

                <div>
                    <label className="flex justify-between text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                        <span>🎩 Formality</span>
                        <span className="text-blue-600">{(personality.formality * 100).toFixed(0)}%</span>
                    </label>
                    <input
                        type="range"
                        min="0"
                        max="1"
                        step="0.1"
                        value={personality.formality}
                        onChange={(e) => handleSliderChange('formality', parseFloat(e.target.value))}
                        className="w-full h-2 bg-zinc-200 dark:bg-zinc-700 rounded-lg appearance-none cursor-pointer accent-blue-600"
                    />
                </div>

                <div>
                    <label className="flex justify-between text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                        <span>💬 Verbosity</span>
                        <span className="text-blue-600">{(personality.verbosity * 100).toFixed(0)}%</span>
                    </label>
                    <input
                        type="range"
                        min="0"
                        max="1"
                        step="0.1"
                        value={personality.verbosity}
                        onChange={(e) => handleSliderChange('verbosity', parseFloat(e.target.value))}
                        className="w-full h-2 bg-zinc-200 dark:bg-zinc-700 rounded-lg appearance-none cursor-pointer accent-blue-600"
                    />
                </div>

                <div>
                    <label className="flex justify-between text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                        <span>⚡ Enthusiasm</span>
                        <span className="text-blue-600">{(personality.enthusiasm * 100).toFixed(0)}%</span>
                    </label>
                    <input
                        type="range"
                        min="0"
                        max="1"
                        step="0.1"
                        value={personality.enthusiasm}
                        onChange={(e) => handleSliderChange('enthusiasm', parseFloat(e.target.value))}
                        className="w-full h-2 bg-zinc-200 dark:bg-zinc-700 rounded-lg appearance-none cursor-pointer accent-blue-600"
                    />
                </div>

                <div>
                    <label className="flex justify-between text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                        <span>🔧 Technical Depth</span>
                        <span className="text-blue-600">{(personality.technical_depth * 100).toFixed(0)}%</span>
                    </label>
                    <input
                        type="range"
                        min="0"
                        max="1"
                        step="0.1"
                        value={personality.technical_depth}
                        onChange={(e) => handleSliderChange('technical_depth', parseFloat(e.target.value))}
                        className="w-full h-2 bg-zinc-200 dark:bg-zinc-700 rounded-lg appearance-none cursor-pointer accent-blue-600"
                    />
                </div>
            </div>

            {/* Apply Button */}
            <button
                onClick={applyPersonality}
                className="w-full px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors font-medium"
            >
                Apply Personality
            </button>

            {customMode && (
                <p className="text-xs text-amber-600 dark:text-amber-400 mt-2 text-center">
                    ⚠️ Custom personality active
                </p>
            )}
        </div>
    );
}
