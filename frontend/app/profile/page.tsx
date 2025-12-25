'use client';

import { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import api from '../../utils/api';
import { useRouter } from 'next/navigation';
import AvatarUpload from '../components/AvatarUpload';
import PasswordChange from '../components/PasswordChange';
import DataExportImport from '../components/DataExportImport';
import DeleteAccountModal from '../components/DeleteAccountModal';

export default function ProfilePage() {
    const { user, login } = useAuth();
    const router = useRouter();
    const [isLoading, setIsLoading] = useState(false);
    const [activeTab, setActiveTab] = useState('profile');
    const [formData, setFormData] = useState({
        // Basic info
        display_name: '',
        first_name: '',
        last_name: '',
        email: '',
        bio: '',

        // Location & Time
        location: '',
        address: '',
        timezone: 'UTC',
        date_of_birth: '',

        // Personalization (for Admin AI)
        occupation: '',
        experience_level: 'beginner',
        interests: [] as string[],
        preferred_media_types: [] as string[],
        content_goals: '',

        // Social
        social_links: {} as Record<string, string>,
        profile_visibility: 'private',

        // Legacy
        custom_data: '{}'
    });
    const [message, setMessage] = useState<{ type: 'success' | 'error', text: string } | null>(null);
    const [interestInput, setInterestInput] = useState('');

    useEffect(() => {
        if (!user) {
            router.push('/login');
            return;
        }

        // Initialize form from user data
        setFormData({
            display_name: user.display_name || '',
            first_name: user.first_name || '',
            last_name: user.last_name || '',
            email: user.email || '',
            bio: user.bio || '',
            location: user.location || '',
            address: user.address || '',
            timezone: user.timezone || 'UTC',
            date_of_birth: user.date_of_birth ? user.date_of_birth.split('T')[0] : '',
            occupation: user.occupation || '',
            experience_level: user.experience_level || 'beginner',
            interests: user.interests || [],
            preferred_media_types: user.preferred_media_types || [],
            content_goals: user.content_goals || '',
            social_links: user.social_links || {},
            profile_visibility: user.profile_visibility || 'private',
            custom_data: JSON.stringify(user.custom_data || {}, null, 2)
        });
    }, [user, router]);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setMessage(null);
        setIsLoading(true);

        try {
            let parsedCustomData = {};
            try {
                parsedCustomData = JSON.parse(formData.custom_data);
            } catch (e) {
                throw new Error('Invalid JSON in Custom Data field');
            }

            const updates = {
                display_name: formData.display_name,
                first_name: formData.first_name,
                last_name: formData.last_name,
                email: formData.email,
                bio: formData.bio,
                location: formData.location,
                address: formData.address,
                timezone: formData.timezone,
                date_of_birth: formData.date_of_birth || null,
                occupation: formData.occupation,
                experience_level: formData.experience_level,
                interests: formData.interests,
                preferred_media_types: formData.preferred_media_types,
                content_goals: formData.content_goals,
                social_links: formData.social_links,
                profile_visibility: formData.profile_visibility,
                custom_data: parsedCustomData
            };

            const updatedUser = await api.updateProfile(updates);

            // Update local storage
            localStorage.setItem('noslop_user', JSON.stringify(updatedUser));

            setMessage({ type: 'success', text: 'Profile updated successfully!' });

            // Refresh page to reload user context after short delay
            setTimeout(() => window.location.reload(), 1500);

        } catch (err: any) {
            setMessage({ type: 'error', text: err.message });
        } finally {
            setIsLoading(false);
        }
    };

    const addInterest = () => {
        if (interestInput.trim() && !formData.interests.includes(interestInput.trim())) {
            setFormData({ ...formData, interests: [...formData.interests, interestInput.trim()] });
            setInterestInput('');
        }
    };

    const removeInterest = (interest: string) => {
        setFormData({ ...formData, interests: formData.interests.filter(i => i !== interest) });
    };

    const toggleMediaType = (type: string) => {
        const types = formData.preferred_media_types;
        if (types.includes(type)) {
            setFormData({ ...formData, preferred_media_types: types.filter(t => t !== type) });
        } else {
            setFormData({ ...formData, preferred_media_types: [...types, type] });
        }
    };

    const handleAvatarUpload = (avatarUrl: string) => {
        // The avatar is already updated on the server by AvatarUpload component
        setMessage({ type: 'success', text: 'Avatar updated! Refresh to see changes.' });
        setTimeout(() => window.location.reload(), 1000);
    };

    if (!user) return null;

    const tabs = [
        { id: 'profile', name: 'Profile', icon: '👤' },
        { id: 'personalization', name: 'Personalization', icon: '🎨' },
        { id: 'security', name: 'Security', icon: '🔒' },
        { id: 'data', name: 'Data', icon: '📦' },
        { id: 'danger', name: 'Danger Zone', icon: '⚠️' }
    ];

    return (
        <div className="min-h-screen bg-gray-900 text-white p-6">
            <div className="max-w-6xl mx-auto">
                <h1 className="text-4xl font-bold mb-2">Your Profile</h1>
                <p className="text-gray-400 mb-8">Manage your account settings and preferences</p>

                {message && (
                    <div className={`p-4 rounded mb-6 ${message.type === 'success' ? 'bg-green-900 text-green-200' : 'bg-red-900 text-red-200'}`}>
                        {message.text}
                    </div>
                )}

                {/* Tabs */}
                <div className="flex gap-2 mb-6 overflow-x-auto pb-2">
                    {tabs.map(tab => (
                        <button
                            key={tab.id}
                            onClick={() => setActiveTab(tab.id)}
                            className={`px-4 py-2 rounded-lg font-medium whitespace-nowrap transition-colors ${activeTab === tab.id
                                ? 'bg-blue-600 text-white'
                                : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
                                }`}
                        >
                            <span className="mr-2">{tab.icon}</span>
                            {tab.name}
                        </button>
                    ))}
                </div>

                <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                    {/* User Card */}
                    <div className="lg:col-span-1">
                        <div className="bg-gray-800 rounded-lg p-6 sticky top-6">
                            <AvatarUpload
                                currentAvatarUrl={user.avatar_url}
                                onUploadSuccess={handleAvatarUpload}
                            />

                            <div className="mt-6 pt-6 border-t border-gray-700">
                                <h3 className="text-xl font-semibold mb-2">{formData.display_name || user.username}</h3>
                                <p className="text-gray-400 text-sm mb-4">@{user.username}</p>

                                <div className="flex flex-wrap gap-2">
                                    <span className={`px-3 py-1 rounded text-xs font-medium ${user.role === 'admin' ? 'bg-purple-900 text-purple-200' : 'bg-gray-700 text-gray-200'
                                        }`}>
                                        {user.role || 'Basic'}
                                    </span>
                                    <span className="px-3 py-1 rounded text-xs font-medium bg-gray-700 text-gray-300">
                                        {user.is_active ? '✓ Active' : '✗ Inactive'}
                                    </span>
                                    {user.email_verified && (
                                        <span className="px-3 py-1 rounded text-xs font-medium bg-green-900 text-green-200">
                                            ✓ Verified
                                        </span>
                                    )}
                                </div>
                            </div>
                        </div>
                    </div>

                    {/* Main Content */}
                    <div className="lg:col-span-2">
                        {activeTab === 'profile' && (
                            <form onSubmit={handleSubmit} className="space-y-6">
                                <div className="bg-gray-800 rounded-lg p-6 space-y-6">
                                    <h2 className="text-2xl font-semibold">Basic Information</h2>

                                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                                        <div>
                                            <label className="block text-gray-400 mb-2 text-sm">Display Name</label>
                                            <input
                                                type="text"
                                                value={formData.display_name}
                                                onChange={(e) => setFormData({ ...formData, display_name: e.target.value })}
                                                className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                                                placeholder="How should we call you?"
                                            />
                                        </div>

                                        <div>
                                            <label className="block text-gray-400 mb-2 text-sm">Email Address</label>
                                            <input
                                                type="email"
                                                value={formData.email}
                                                onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                                                className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                                            />
                                        </div>
                                    </div>

                                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                                        <div>
                                            <label className="block text-gray-400 mb-2 text-sm">First Name</label>
                                            <input
                                                type="text"
                                                value={formData.first_name}
                                                onChange={(e) => setFormData({ ...formData, first_name: e.target.value })}
                                                className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                                            />
                                        </div>

                                        <div>
                                            <label className="block text-gray-400 mb-2 text-sm">Last Name</label>
                                            <input
                                                type="text"
                                                value={formData.last_name}
                                                onChange={(e) => setFormData({ ...formData, last_name: e.target.value })}
                                                className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                                            />
                                        </div>
                                    </div>

                                    <div>
                                        <label className="block text-gray-400 mb-2 text-sm">Bio</label>
                                        <textarea
                                            value={formData.bio}
                                            onChange={(e) => setFormData({ ...formData, bio: e.target.value })}
                                            className="w-full bg-gray-700 rounded p-3 text-white h-24 focus:ring-2 focus:ring-blue-500 outline-none"
                                            placeholder="Tell us about yourself..."
                                        />
                                    </div>

                                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                                        <div>
                                            <label className="block text-gray-400 mb-2 text-sm">Location</label>
                                            <input
                                                type="text"
                                                value={formData.location}
                                                onChange={(e) => setFormData({ ...formData, location: e.target.value })}
                                                className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                                                placeholder="City, Country"
                                            />
                                        </div>

                                        <div>
                                            <label className="block text-gray-400 mb-2 text-sm">Timezone</label>
                                            <select
                                                value={formData.timezone}
                                                onChange={(e) => setFormData({ ...formData, timezone: e.target.value })}
                                                className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
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

                                    <div>
                                        <label className="block text-gray-400 mb-2 text-sm">Full Address (Private/Local only)</label>
                                        <input
                                            type="text"
                                            value={formData.address}
                                            onChange={(e) => setFormData({ ...formData, address: e.target.value })}
                                            className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                                            placeholder="Street, State, Zip Code"
                                        />
                                    </div>

                                    <div>
                                        <label className="block text-gray-400 mb-2 text-sm">Date of Birth</label>
                                        <input
                                            type="date"
                                            value={formData.date_of_birth}
                                            onChange={(e) => setFormData({ ...formData, date_of_birth: e.target.value })}
                                            className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                                        />
                                    </div>
                                </div>

                                <div className="flex justify-end">
                                    <button
                                        type="submit"
                                        disabled={isLoading}
                                        className="bg-blue-600 hover:bg-blue-700 text-white px-8 py-3 rounded-lg font-medium transition-colors disabled:opacity-50"
                                    >
                                        {isLoading ? 'Saving...' : 'Save Changes'}
                                    </button>
                                </div>
                            </form>
                        )}

                        {activeTab === 'personalization' && (
                            <form onSubmit={handleSubmit} className="space-y-6">
                                <div className="bg-gray-800 rounded-lg p-6 space-y-6">
                                    <div>
                                        <h2 className="text-2xl font-semibold mb-2">Personalization</h2>
                                        <p className="text-gray-400 text-sm">Help Admin AI understand you better</p>
                                    </div>

                                    <div>
                                        <label className="block text-gray-400 mb-2 text-sm">Profession / Occupation</label>
                                        <input
                                            type="text"
                                            value={formData.occupation}
                                            onChange={(e) => setFormData({ ...formData, occupation: e.target.value })}
                                            className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                                            placeholder="e.g., Video Editor, Content Creator, Photographer"
                                        />
                                    </div>

                                    <div>
                                        <label className="block text-gray-400 mb-2 text-sm">Experience Level</label>
                                        <select
                                            value={formData.experience_level}
                                            onChange={(e) => setFormData({ ...formData, experience_level: e.target.value })}
                                            className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                                        >
                                            <option value="beginner">Beginner - Just getting started</option>
                                            <option value="intermediate">Intermediate - Some experience</option>
                                            <option value="advanced">Advanced - Experienced user</option>
                                            <option value="expert">Expert - Professional level</option>
                                        </select>
                                    </div>

                                    <div>
                                        <label className="block text-gray-400 mb-2 text-sm">Interests & Hobbies</label>
                                        <div className="flex gap-2 mb-2">
                                            <input
                                                type="text"
                                                value={interestInput}
                                                onChange={(e) => setInterestInput(e.target.value)}
                                                onKeyPress={(e) => e.key === 'Enter' && (e.preventDefault(), addInterest())}
                                                className="flex-1 bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                                                placeholder="Add interests (press Enter)"
                                            />
                                            <button
                                                type="button"
                                                onClick={addInterest}
                                                className="bg-blue-600 hover:bg-blue-700 text-white px-4 rounded transition-colors"
                                            >
                                                Add
                                            </button>
                                        </div>
                                        <div className="flex flex-wrap gap-2">
                                            {formData.interests.map(interest => (
                                                <span key={interest} className="bg-blue-900/50 text-blue-200 px-3 py-1 rounded-full text-sm flex items-center gap-2">
                                                    {interest}
                                                    <button
                                                        type="button"
                                                        onClick={() => removeInterest(interest)}
                                                        className="hover:text-red-400"
                                                    >
                                                        ✕
                                                    </button>
                                                </span>
                                            ))}
                                        </div>
                                    </div>

                                    <div>
                                        <label className="block text-gray-400 mb-2 text-sm">Preferred Media Types</label>
                                        <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
                                            {['Video', 'Podcast', 'Blog', 'Photography', 'Music', 'Animation'].map(type => (
                                                <label key={type} className="flex items-center gap-2 cursor-pointer bg-gray-700 p-3 rounded hover:bg-gray-600 transition-colors">
                                                    <input
                                                        type="checkbox"
                                                        checked={formData.preferred_media_types.includes(type.toLowerCase())}
                                                        onChange={() => toggleMediaType(type.toLowerCase())}
                                                        className="w-4 h-4"
                                                    />
                                                    <span>{type}</span>
                                                </label>
                                            ))}
                                        </div>
                                    </div>

                                    <div>
                                        <label className="block text-gray-400 mb-2 text-sm">Content Goals</label>
                                        <textarea
                                            value={formData.content_goals}
                                            onChange={(e) => setFormData({ ...formData, content_goals: e.target.value })}
                                            className="w-full bg-gray-700 rounded p-3 text-white h-32 focus:ring-2 focus:ring-blue-500 outline-none"
                                            placeholder="What do you want to create or achieve? Be specific..."
                                        />
                                    </div>
                                </div>

                                <div className="flex justify-end">
                                    <button
                                        type="submit"
                                        disabled={isLoading}
                                        className="bg-blue-600 hover:bg-blue-700 text-white px-8 py-3 rounded-lg font-medium transition-colors disabled:opacity-50"
                                    >
                                        {isLoading ? 'Saving...' : 'Save Changes'}
                                    </button>
                                </div>
                            </form>
                        )}

                        {activeTab === 'security' && (
                            <div className="space-y-6">
                                <PasswordChange />
                            </div>
                        )}

                        {activeTab === 'data' && (
                            <div className="space-y-6">
                                <DataExportImport />
                            </div>
                        )}

                        {activeTab === 'danger' && (
                            <div className="space-y-6">
                                <DeleteAccountModal />
                            </div>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}
