import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import api from '../../utils/api';

interface ProfileModalProps {
    isOpen: boolean;
    onClose: () => void;
}

export default function ProfileModal({ isOpen, onClose }: ProfileModalProps) {
    const { user, login } = useAuth(); // We might need to update user context
    const [isLoading, setIsLoading] = useState(false);
    const [profile, setProfile] = useState({
        bio: '',
        role: '',
        interests: [] as string[]
    });
    const [personality, setPersonality] = useState<any>(null);

    // Load initial data
    useEffect(() => {
        if (isOpen && user) {
            setProfile({
                bio: user.bio || '',
                role: user.custom_data?.role || '',
                interests: user.custom_data?.interests || []
            });
            // Construct personality from flat user fields if available
            if (user.personality) {
                setPersonality({
                    type: user.personality.type,
                    formality: user.personality.formality,
                    enthusiasm: user.personality.enthusiasm,
                    verbosity: user.personality.verbosity
                });
            }
        }
    }, [isOpen, user]);

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

    const handleSave = async () => {
        setIsLoading(true);
        try {
            const updatedUser = await api.updateProfile({
                bio: profile.bio,
                custom_data: {
                    ...(user?.custom_data || {}),
                    role: profile.role,
                    interests: profile.interests
                },
                // Pass back personality if we were editing it (optional feature)
            });

            // We need a way to update the local user context without full re-login
            // For now, simple reload or we rely on the fact that AuthContext might not expose a direct setter
            // Ideally notify user
            window.location.reload(); // Simple way to refresh context
            onClose();
        } catch (error) {
            console.error("Failed to update profile:", error);
            alert("Failed to save changes.");
        } finally {
            setIsLoading(false);
        }
    };

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex items-center justify-center p-4">
            <div className="bg-white dark:bg-zinc-900 rounded-xl shadow-2xl max-w-2xl w-full max-h-[90vh] overflow-y-auto">
                <div className="p-6 border-b border-zinc-200 dark:border-zinc-800 flex justify-between items-center">
                    <h2 className="text-xl font-bold text-zinc-900 dark:text-zinc-50">
                        Edit Profile
                    </h2>
                    <button onClick={onClose} className="text-zinc-500 hover:text-zinc-700 dark:hover:text-zinc-300">
                        ✕
                    </button>
                </div>

                <div className="p-6 space-y-6">
                    <div className="flex items-center space-x-4 mb-6">
                        <div className="w-16 h-16 bg-blue-100 dark:bg-blue-900/30 rounded-full flex items-center justify-center text-2xl">
                            👤
                        </div>
                        <div>
                            <h3 className="font-semibold text-lg">{user?.username}</h3>
                            <p className="text-sm text-zinc-500">{user?.email || 'No email set'}</p>
                        </div>
                    </div>

                    <div className="space-y-4">
                        <div>
                            <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                                Primary Role
                            </label>
                            <input
                                type="text"
                                value={profile.role}
                                onChange={(e) => handleProfileChange('role', e.target.value)}
                                className="w-full px-4 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 bg-zinc-50 dark:bg-zinc-800"
                            />
                        </div>

                        <div>
                            <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                                Bio
                            </label>
                            <textarea
                                value={profile.bio}
                                onChange={(e) => handleProfileChange('bio', e.target.value)}
                                className="w-full px-4 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 bg-zinc-50 dark:bg-zinc-800 h-24 resize-none"
                            />
                        </div>

                        <div>
                            <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-2">
                                Interests
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
                </div>

                <div className="p-6 border-t border-zinc-200 dark:border-zinc-800 flex justify-end space-x-3">
                    <button
                        onClick={onClose}
                        className="px-4 py-2 text-zinc-600 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-200"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={handleSave}
                        disabled={isLoading}
                        className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50"
                    >
                        {isLoading ? 'Saving...' : 'Save Changes'}
                    </button>
                </div>
            </div>
        </div>
    );
}
