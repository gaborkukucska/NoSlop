import React, { useState } from 'react';
import { useAuth } from '../context/AuthContext';
import api from '../../utils/api';

interface SetupStep {
    id: 'welcome' | 'personality' | 'about' | 'finish';
    title: string;
}

interface SetupWizardProps {
    onComplete: () => void;
}

export default function SetupWizard({ onComplete }: SetupWizardProps) {
    const { user } = useAuth();
    const [step, setStep] = useState<SetupStep['id']>('welcome');
    const [isSubmitting, setIsSubmitting] = useState(false);

    // Personality state
    const [personality, setPersonality] = useState({
        type: 'balanced',
        formality: 0.5,
        enthusiasm: 0.7,
        verbosity: 0.6
    });

    // Profile state
    const [profile, setProfile] = useState({
        bio: '',
        role: '',
        interests: [] as string[]
    });

    const handlePersonalityChange = (field: string, value: any) => {
        setPersonality(prev => ({ ...prev, [field]: value }));
    };

    const handleProfileChange = (field: string, value: any) => {
        setProfile(prev => ({ ...prev, [field]: value }));
    };

    const toggleInterest = (interest: string) => {
        setProfile(prev => {
            const interests = prev.interests.includes(interest)
                ? prev.interests.filter(i => i !== interest)
                : [...prev.interests, interest];
            return { ...prev, interests };
        });
    };

    const finishSetup = async () => {
        setIsSubmitting(true);
        try {
            await api.completeSetup({
                personality,
                ...profile,
                custom_data: { role: profile.role, interests: profile.interests }
            });
            onComplete();
        } catch (error) {
            console.error("Setup failed:", error);
        } finally {
            setIsSubmitting(false);
        }
    };

    // --- STEPS RENDERERS ---

    const renderWelcome = () => (
        <div className="space-y-6 text-center animate-fadeIn">
            <div className="text-6xl mb-4">👋</div>
            <h2 className="text-2xl font-bold text-zinc-900 dark:text-zinc-50">
                Welcome to NoSlop, {user?.username}!
            </h2>
            <p className="text-zinc-600 dark:text-zinc-400 max-w-md mx-auto">
                I'm your Admin AI. Before we start creating amazing media, I'd like to get to know your preferences.
            </p>
            <div className="pt-4">
                <button
                    onClick={() => setStep('personality')}
                    className="px-8 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-all transform hover:scale-105 font-medium shadow-lg shadow-blue-500/30"
                >
                    Let's Get Started
                </button>
            </div>
        </div>
    );

    const renderPersonality = () => (
        <div className="space-y-6 animate-fadeIn">
            <div className="text-center">
                <h2 className="text-2xl font-bold text-zinc-900 dark:text-zinc-50">
                    Customize My Personality
                </h2>
                <p className="text-zinc-600 dark:text-zinc-400">
                    How should I behave when assisting you?
                </p>
            </div>

            <div className="space-y-6 bg-zinc-50 dark:bg-zinc-800/50 p-6 rounded-xl border border-zinc-200 dark:border-zinc-700">
                {/* Mode Selection */}
                <div className="grid grid-cols-3 gap-3">
                    {['creative', 'balanced', 'technical'].map((type) => (
                        <button
                            key={type}
                            onClick={() => handlePersonalityChange('type', type)}
                            className={`px-4 py-3 rounded-lg border-2 capitalize transition-all ${personality.type === type
                                    ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/20 text-blue-700 dark:text-blue-300'
                                    : 'border-transparent bg-white dark:bg-zinc-800 hover:border-zinc-300 dark:hover:border-zinc-600'
                                }`}
                        >
                            {type}
                        </button>
                    ))}
                </div>

                {/* Sliders */}
                <div className="space-y-4">
                    <div>
                        <div className="flex justify-between mb-1">
                            <label className="text-sm font-medium">Enthusiasm</label>
                            <span className="text-xs text-zinc-500">{Math.round(personality.enthusiasm * 100)}%</span>
                        </div>
                        <input
                            type="range"
                            min="0"
                            max="1"
                            step="0.1"
                            value={personality.enthusiasm}
                            onChange={(e) => handlePersonalityChange('enthusiasm', parseFloat(e.target.value))}
                            className="w-full h-2 bg-zinc-200 rounded-lg appearance-none cursor-pointer dark:bg-zinc-700 accent-blue-600"
                        />
                    </div>
                </div>
            </div>

            <div className="flex justify-between pt-4">
                <button
                    onClick={() => setStep('welcome')}
                    className="px-4 py-2 text-zinc-600 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-200"
                >
                    Back
                </button>
                <button
                    onClick={() => setStep('about')}
                    className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
                >
                    Next
                </button>
            </div>
        </div>
    );

    const renderAboutYou = () => (
        <div className="space-y-6 animate-fadeIn">
            <div className="text-center">
                <h2 className="text-2xl font-bold text-zinc-900 dark:text-zinc-50">
                    Tell Me About Yourself
                </h2>
                <p className="text-zinc-600 dark:text-zinc-400">
                    This helps me tailor my suggestions to your creative needs.
                </p>
            </div>

            <div className="space-y-4">
                <div>
                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                        What's your primary role?
                    </label>
                    <input
                        type="text"
                        value={profile.role}
                        onChange={(e) => handleProfileChange('role', e.target.value)}
                        placeholder="e.g. Filmmaker, Content Creator, Developer, Student"
                        className="w-full px-4 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-2 focus:ring-blue-500 outline-none transition-all"
                    />
                </div>

                <div>
                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                        A short bio (optional)
                    </label>
                    <textarea
                        value={profile.bio}
                        onChange={(e) => handleProfileChange('bio', e.target.value)}
                        placeholder="What are your creative goals? What do you enjoy making?"
                        className="w-full px-4 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-2 focus:ring-blue-500 outline-none transition-all h-24 resize-none"
                    />
                </div>

                <div>
                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-2">
                        Interests (Select all that apply)
                    </label>
                    <div className="flex flex-wrap gap-2">
                        {['Video Production', 'Scriptwriting', 'Animation', 'Social Media', 'Music', 'Coding', 'Marketing', 'Education'].map((interest) => (
                            <button
                                key={interest}
                                onClick={() => toggleInterest(interest)}
                                className={`px-3 py-1.5 rounded-full text-sm font-medium transition-colors ${profile.interests.includes(interest)
                                        ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300 ring-2 ring-blue-500/20'
                                        : 'bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400 hover:bg-zinc-200 dark:hover:bg-zinc-700'
                                    }`}
                            >
                                {interest}
                            </button>
                        ))}
                    </div>
                </div>
            </div>

            <div className="flex justify-between pt-4">
                <button
                    onClick={() => setStep('personality')}
                    className="px-4 py-2 text-zinc-600 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-200"
                >
                    Back
                </button>
                <button
                    onClick={() => setStep('finish')}
                    className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
                >
                    Next
                </button>
            </div>
        </div>
    );

    const renderFinish = () => (
        <div className="space-y-6 text-center animate-fadeIn">
            <div className="text-6xl mb-4">🚀</div>
            <h2 className="text-2xl font-bold text-zinc-900 dark:text-zinc-50">
                All Set!
            </h2>
            <p className="text-zinc-600 dark:text-zinc-400 max-w-md mx-auto">
                I'm ready to help you manage your projects. Establishing neural link now...
            </p>

            <div className="pt-6">
                <button
                    onClick={finishSetup}
                    disabled={isSubmitting}
                    className="px-8 py-3 bg-green-600 text-white rounded-lg hover:bg-green-700 transition-all transform hover:scale-105 font-medium shadow-lg shadow-green-500/30 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center w-full sm:w-auto mx-auto"
                >
                    {isSubmitting ? (
                        <>
                            <svg className="animate-spin -ml-1 mr-3 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                            </svg>
                            Initialising...
                        </>
                    ) : (
                        "Enter Studio"
                    )}
                </button>
            </div>
        </div>
    );

    return (
        <div className="bg-white dark:bg-zinc-900 rounded-2xl shadow-2xl max-w-2xl w-full mx-auto p-8 relative overflow-hidden">
            {/* Background Decoration */}
            <div className="absolute top-0 right-0 -mt-16 -mr-16 w-64 h-64 bg-blue-500/10 rounded-full blur-3xl pointer-events-none"></div>
            <div className="absolute bottom-0 left-0 -mb-16 -ml-16 w-64 h-64 bg-purple-500/10 rounded-full blur-3xl pointer-events-none"></div>

            <div className="relative z-10">
                {step === 'welcome' && renderWelcome()}
                {step === 'personality' && renderPersonality()}
                {step === 'about' && renderAboutYou()}
                {step === 'finish' && renderFinish()}
            </div>

            {/* Progress Dots */}
            <div className="flex justify-center space-x-2 mt-8">
                {['welcome', 'personality', 'about', 'finish'].map((s) => (
                    <div
                        key={s}
                        className={`w-2 h-2 rounded-full transition-colors ${step === s ? 'bg-blue-600' : 'bg-zinc-200 dark:bg-zinc-700'
                            }`}
                    />
                ))}
            </div>
        </div>
    );
}
