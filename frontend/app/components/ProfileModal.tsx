import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import api from '../../utils/api';
import AvatarUpload from './AvatarUpload';
import PasswordChange from './PasswordChange';
import DataExportImport from './DataExportImport';
import DeleteAccountModal from './DeleteAccountModal';

interface ProfileModalProps {
    isOpen: boolean;
    onClose: () => void;
}

type TabType = 'profile' | 'personal' | 'personalization' | 'ai' | 'security' | 'data' | 'danger';

export default function ProfileModal({ isOpen, onClose }: ProfileModalProps) {
    const { user, refreshUser } = useAuth();
    const [activeTab, setActiveTab] = useState<TabType>('profile');
    const [isSaving, setIsSaving] = useState(false);
    const [showDeleteModal, setShowDeleteModal] = useState(false);
    const [successMessage, setSuccessMessage] = useState('');

    // Form data state
    const [formData, setFormData] = useState({
        display_name: '',
        bio: '',
        avatar_url: '',
        first_name: '',
        last_name: '',
        date_of_birth: '',
        location: '',
        address: '',
        timezone: 'UTC',
        interests: [] as string[],
        occupation: '',
        experience_level: 'beginner',
        preferred_media_types: [] as string[],
        content_goals: '',
        social_links: {} as Record<string, string>,
        profile_visibility: 'private',
        personality: {
            type: 'balanced',
            formality: 0.5,
            enthusiasm: 0.7,
            verbosity: 0.6
        }
    });

    // Load user data when modal opens
    useEffect(() => {
        if (isOpen && user) {
            setFormData({
                display_name: user.display_name || '',
                bio: user.bio || '',
                avatar_url: user.avatar_url || '',
                first_name: user.first_name || '',
                last_name: user.last_name || '',
                date_of_birth: user.date_of_birth || '',
                location: user.location || '',
                address: user.address || '',
                timezone: user.timezone || 'UTC',
                interests: user.interests || [],
                occupation: user.occupation || '',
                experience_level: user.experience_level || 'beginner',
                preferred_media_types: user.preferred_media_types || [],
                content_goals: user.content_goals || '',
                social_links: user.social_links || {},
                profile_visibility: user.profile_visibility || 'private',
                personality: user.personality || {
                    type: 'balanced',
                    formality: 0.5,
                    enthusiasm: 0.7,
                    verbosity: 0.6
                }
            });
        }
    }, [isOpen, user]);

    const handleChange = (field: string, value: any) => {
        setFormData(prev => ({ ...prev, [field]: value }));
    };

    const toggleInterest = (interest: string) => {
        setFormData(prev => ({
            ...prev,
            interests: prev.interests.includes(interest)
                ? prev.interests.filter(i => i !== interest)
                : [...prev.interests, interest]
        }));
    };

    const toggleMediaType = (type: string) => {
        setFormData(prev => ({
            ...prev,
            preferred_media_types: prev.preferred_media_types.includes(type)
                ? prev.preferred_media_types.filter(t => t !== type)
                : [...prev.preferred_media_types, type]
        }));
    };

    const handleSave = async () => {
        setIsSaving(true);
        setSuccessMessage('');
        try {
            const updates = {
                ...formData,
                interests: formData.interests,
                preferred_media_types: formData.preferred_media_types,
                // Convert empty strings to null for date fields
                date_of_birth: formData.date_of_birth || null
            };

            await api.updateProfile(updates);
            await refreshUser();
            setSuccessMessage('Profile updated successfully!');
            setTimeout(() => setSuccessMessage(''), 3000);
        } catch (error) {
            console.error('Failed to update profile:', error);
            alert('Failed to update profile. Please try again.');
        } finally {
            setIsSaving(false);
        }
    };

    const handleAvatarUploadSuccess = async (avatarUrl: string) => {
        await refreshUser();
        setFormData(prev => ({ ...prev, avatar_url: avatarUrl }));
    };

    if (!isOpen) return null;

    const tabs: { id: TabType; label: string; icon: string }[] = [
        { id: 'profile', label: 'Profile', icon: '👤' },
        { id: 'personal', label: 'Personal', icon: '📋' },
        { id: 'personalization', label: 'Interests', icon: '🎨' },
        { id: 'ai', label: 'AI Settings', icon: '🤖' },
        { id: 'security', label: 'Security', icon: '🔒' },
        { id: 'data', label: 'Data', icon: '💾' },
        { id: 'danger', label: 'Danger Zone', icon: '⚠️' }
    ];

    return (
        <>
            <div className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex items-center justify-center p-4" onClick={onClose}>
                <div
                    className="bg-white dark:bg-zinc-900 rounded-2xl shadow-2xl max-w-4xl w-full max-h-[90vh] overflow-hidden flex flex-col"
                    onClick={(e) => e.stopPropagation()}
                >
                    {/* Header */}
                    <div className="px-6 py-4 border-b border-zinc-200 dark:border-zinc-800 flex justify-between items-center">
                        <h2 className="text-2xl font-bold text-zinc-900 dark:text-zinc-50">Edit Profile</h2>
                        <button
                            onClick={onClose}
                            className="text-zinc-400 hover:text-zinc-600 dark:hover:text-zinc-200 transition-colors"
                        >
                            <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12"></path>
                            </svg>
                        </button>
                    </div>

                    {/* Success Message */}
                    {successMessage && (
                        <div className="mx-6 mt-4 px-4 py-3 bg-green-100 dark:bg-green-900/30 border border-green-200 dark:border-green-800 rounded-lg text-green-800 dark:text-green-200">
                            {successMessage}
                        </div>
                    )}

                    {/* Tabs */}
                    <div className="px-6 pt-4 border-b border-zinc-200 dark:border-zinc-800 overflow-x-auto">
                        <div className="flex gap-2 min-w-max">
                            {tabs.map(tab => (
                                <button
                                    key={tab.id}
                                    onClick={() => setActiveTab(tab.id)}
                                    className={`px-4 py-2 rounded-t-lg font-medium transition-colors whitespace-nowrap ${activeTab === tab.id
                                        ? 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 border-b-2 border-blue-600'
                                        : 'text-zinc-600 dark:text-zinc-400 hover:bg-zinc-100 dark:hover:bg-zinc-800'
                                        }`}
                                >
                                    <span className="mr-2">{tab.icon}</span>
                                    {tab.label}
                                </button>
                            ))}
                        </div>
                    </div>

                    {/* Content */}
                    <div className="flex-1 overflow-y-auto p-6">
                        {/* Profile Tab */}
                        {activeTab === 'profile' && (
                            <div className="space-y-6">
                                <AvatarUpload
                                    currentAvatarUrl={formData.avatar_url}
                                    onUploadSuccess={handleAvatarUploadSuccess}
                                />

                                <div>
                                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                                        Display Name
                                    </label>
                                    <input
                                        type="text"
                                        value={formData.display_name}
                                        onChange={(e) => handleChange('display_name', e.target.value)}
                                        placeholder="How should we address you?"
                                        className="w-full px-4 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-2 focus:ring-blue-500 outline-none"
                                    />
                                </div>

                                <div>
                                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                                        Bio
                                    </label>
                                    <textarea
                                        value={formData.bio}
                                        onChange={(e) => handleChange('bio', e.target.value)}
                                        placeholder="Tell us about yourself..."
                                        rows={4}
                                        className="w-full px-4 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-2 focus:ring-blue-500 outline-none resize-none"
                                    />
                                </div>
                            </div>
                        )}

                        {/* Personal Tab */}
                        {activeTab === 'personal' && (
                            <div className="space-y-4">
                                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                                    <div>
                                        <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                                            First Name
                                        </label>
                                        <input
                                            type="text"
                                            value={formData.first_name}
                                            onChange={(e) => handleChange('first_name', e.target.value)}
                                            className="w-full px-4 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-2 focus:ring-blue-500 outline-none"
                                        />
                                    </div>
                                    <div>
                                        <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                                            Last Name
                                        </label>
                                        <input
                                            type="text"
                                            value={formData.last_name}
                                            onChange={(e) => handleChange('last_name', e.target.value)}
                                            className="w-full px-4 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-2 focus:ring-blue-500 outline-none"
                                        />
                                    </div>
                                </div>

                                <div>
                                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                                        Location
                                    </label>
                                    <input
                                        type="text"
                                        value={formData.location}
                                        onChange={(e) => handleChange('location', e.target.value)}
                                        placeholder="City, Country"
                                        className="w-full px-4 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-2 focus:ring-blue-500 outline-none"
                                    />
                                </div>

                                <div>
                                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                                        Address (Private)
                                    </label>
                                    <input
                                        type="text"
                                        value={formData.address}
                                        onChange={(e) => handleChange('address', e.target.value)}
                                        placeholder="Stored locally only"
                                        className="w-full px-4 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-2 focus:ring-blue-500 outline-none"
                                    />
                                </div>

                                <div>
                                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                                        Timezone
                                    </label>
                                    <select
                                        value={formData.timezone}
                                        onChange={(e) => handleChange('timezone', e.target.value)}
                                        className="w-full px-4 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-2 focus:ring-blue-500 outline-none"
                                    >
                                        <option value="UTC">UTC</option>
                                        <option value="America/New_York">Eastern Time</option>
                                        <option value="America/Chicago">Central Time</option>
                                        <option value="America/Denver">Mountain Time</option>
                                        <option value="America/Los_Angeles">Pacific Time</option>
                                        <option value="Europe/London">London</option>
                                        <option value="Europe/Paris">Paris</option>
                                        <option value="Asia/Tokyo">Tokyo</option>
                                        <option value="Asia/Shanghai">Shanghai</option>
                                        <option value="Australia/Sydney">Sydney</option>
                                    </select>
                                </div>
                            </div>
                        )}

                        {/* Personalization Tab */}
                        {activeTab === 'personalization' && (
                            <div className="space-y-6">
                                <div>
                                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                                        Profession / Occupation
                                    </label>
                                    <input
                                        type="text"
                                        value={formData.occupation}
                                        onChange={(e) => handleChange('occupation', e.target.value)}
                                        placeholder="e.g., Video Editor, Developer"
                                        className="w-full px-4 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-2 focus:ring-blue-500 outline-none"
                                    />
                                </div>

                                <div>
                                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-2">
                                        Interests
                                    </label>
                                    <div className="flex flex-wrap gap-2">
                                        {['Video Production', 'Scriptwriting', 'Animation', 'Social Media', 'Music', 'Coding', 'Marketing', 'Education', 'Photography', 'Podcasting'].map((interest) => (
                                            <button
                                                key={interest}
                                                onClick={() => toggleInterest(interest)}
                                                className={`px-3 py-1.5 rounded-full text-sm font-medium transition-colors ${formData.interests.includes(interest)
                                                    ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300 ring-2 ring-blue-500/20'
                                                    : 'bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400 hover:bg-zinc-200 dark:hover:bg-zinc-700'
                                                    }`}
                                            >
                                                {interest}
                                            </button>
                                        ))}
                                    </div>
                                </div>

                                <div>
                                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-2">
                                        Preferred Media Types
                                    </label>
                                    <div className="flex flex-wrap gap-2">
                                        {['Video', 'Podcast', 'Blog', 'Social Posts', 'Music', 'Animation'].map((type) => (
                                            <button
                                                key={type}
                                                onClick={() => toggleMediaType(type)}
                                                className={`px-3 py-1.5 rounded-full text-sm font-medium transition-colors ${formData.preferred_media_types.includes(type)
                                                    ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300 ring-2 ring-blue-500/20'
                                                    : 'bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400 hover:bg-zinc-200 dark:hover:bg-zinc-700'
                                                    }`}
                                            >
                                                {type}
                                            </button>
                                        ))}
                                    </div>
                                </div>

                                <div>
                                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-1">
                                        Content Goals
                                    </label>
                                    <textarea
                                        value={formData.content_goals}
                                        onChange={(e) => handleChange('content_goals', e.target.value)}
                                        placeholder="What do you want to create? What are your goals?"
                                        rows={4}
                                        className="w-full px-4 py-2 rounded-lg border border-zinc-300 dark:border-zinc-700 bg-white dark:bg-zinc-800 focus:ring-2 focus:ring-blue-500 outline-none resize-none"
                                    />
                                </div>
                            </div>
                        )}

                        {/* AI Settings Tab */}
                        {activeTab === 'ai' && (
                            <div className="space-y-6">
                                <div>
                                    <label className="block text-sm font-medium text-zinc-700 dark:text-zinc-300 mb-2">
                                        Personality Type
                                    </label>
                                    <div className="grid grid-cols-3 gap-3">
                                        {['creative', 'balanced', 'technical'].map((type) => (
                                            <button
                                                key={type}
                                                onClick={() => handleChange('personality', { ...formData.personality, type })}
                                                className={`px-4 py-3 rounded-lg border-2 capitalize transition-all ${formData.personality.type === type
                                                    ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/20 text-blue-700 dark:text-blue-300'
                                                    : 'border-transparent bg-zinc-100 dark:bg-zinc-800 hover:border-zinc-300 dark:hover:border-zinc-600'
                                                    }`}
                                            >
                                                {type}
                                            </button>
                                        ))}
                                    </div>
                                </div>

                                <div>
                                    <div className="flex justify-between mb-1">
                                        <label className="text-sm font-medium text-zinc-700 dark:text-zinc-300">Enthusiasm</label>
                                        <span className="text-xs text-zinc-500">{Math.round(formData.personality.enthusiasm * 100)}%</span>
                                    </div>
                                    <input
                                        type="range"
                                        min="0"
                                        max="1"
                                        step="0.1"
                                        value={formData.personality.enthusiasm}
                                        onChange={(e) => handleChange('personality', { ...formData.personality, enthusiasm: parseFloat(e.target.value) })}
                                        className="w-full h-2 bg-zinc-200 rounded-lg appearance-none cursor-pointer dark:bg-zinc-700 accent-blue-600"
                                    />
                                </div>

                                <div>
                                    <div className="flex justify-between mb-1">
                                        <label className="text-sm font-medium text-zinc-700 dark:text-zinc-300">Formality</label>
                                        <span className="text-xs text-zinc-500">{Math.round(formData.personality.formality * 100)}%</span>
                                    </div>
                                    <input
                                        type="range"
                                        min="0"
                                        max="1"
                                        step="0.1"
                                        value={formData.personality.formality}
                                        onChange={(e) => handleChange('personality', { ...formData.personality, formality: parseFloat(e.target.value) })}
                                        className="w-full h-2 bg-zinc-200 rounded-lg appearance-none cursor-pointer dark:bg-zinc-700 accent-blue-600"
                                    />
                                </div>

                                <div>
                                    <div className="flex justify-between mb-1">
                                        <label className="text-sm font-medium text-zinc-700 dark:text-zinc-300">Verbosity</label>
                                        <span className="text-xs text-zinc-500">{Math.round(formData.personality.verbosity * 100)}%</span>
                                    </div>
                                    <input
                                        type="range"
                                        min="0"
                                        max="1"
                                        step="0.1"
                                        value={formData.personality.verbosity}
                                        onChange={(e) => handleChange('personality', { ...formData.personality, verbosity: parseFloat(e.target.value) })}
                                        className="w-full h-2 bg-zinc-200 rounded-lg appearance-none cursor-pointer dark:bg-zinc-700 accent-blue-600"
                                    />
                                </div>
                            </div>
                        )}

                        {/* Security Tab */}
                        {activeTab === 'security' && (
                            <div className="space-y-6">
                                <PasswordChange />
                            </div>
                        )}

                        {/* Data Tab */}
                        {activeTab === 'data' && (
                            <div className="space-y-6">
                                <DataExportImport />
                            </div>
                        )}

                        {/* Danger Zone Tab */}
                        {activeTab === 'danger' && (
                            <div className="space-y-6">
                                <div className="border-2 border-red-200 dark:border-red-900 rounded-lg p-6 bg-red-50 dark:bg-red-900/10">
                                    <h3 className="text-lg font-bold text-red-700 dark:text-red-400 mb-2">
                                        Delete Account
                                    </h3>
                                    <p className="text-sm text-red-600 dark:text-red-300 mb-4">
                                        Once you delete your account, there is no going back. This will permanently delete all your data, projects, and chat history.
                                    </p>
                                    <button
                                        onClick={() => setShowDeleteModal(true)}
                                        className="px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 transition-colors font-medium"
                                    >
                                        Delete My Account
                                    </button>
                                </div>
                            </div>
                        )}
                    </div>

                    {/* Footer */}
                    <div className="px-6 py-4 border-t border-zinc-200 dark:border-zinc-800 flex justify-end gap-3">
                        <button
                            onClick={onClose}
                            className="px-4 py-2 text-zinc-600 dark:text-zinc-400 hover:text-zinc-900 dark:hover:text-zinc-200 transition-colors"
                        >
                            Cancel
                        </button>
                        <button
                            onClick={handleSave}
                            disabled={isSaving}
                            className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed font-medium"
                        >
                            {isSaving ? 'Saving...' : 'Save Changes'}
                        </button>
                    </div>
                </div>
            </div>

            {/* Delete Account Modal - manages its own state */}
            {showDeleteModal && <DeleteAccountModal />}
        </>
    );
}
