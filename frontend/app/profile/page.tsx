'use client';

import { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import api from '../../utils/api';
import { useRouter } from 'next/navigation';

export default function ProfilePage() {
    const { user, login } = useAuth();
    const router = useRouter();
    const [isLoading, setIsLoading] = useState(false);
    const [formData, setFormData] = useState({
        bio: '',
        custom_data: '{}',
        email: ''
    });
    const [message, setMessage] = useState<{ type: 'success' | 'error', text: string } | null>(null);

    useEffect(() => {
        if (!user) {
            router.push('/login');
            return;
        }

        // Initialize form
        setFormData({
            bio: user.bio || '',
            custom_data: JSON.stringify(user.custom_data || {}, null, 2),
            email: user.email || ''
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
                bio: formData.bio,
                custom_data: parsedCustomData,
                email: formData.email
            };

            // Call API
            // We reuse adminUpdateUser or create a generic updateSelf?
            // The backend has /api/users/{id} for self update
            // We need a method in api.ts for this OR use request directly. 
            // api.ts doesn't have updateUserProfile yet explicitly exposed as generic for self?
            // It has adminUpdateUser.
            // Let's us request helper if needed or add to api.ts. 
            // Wait, I added adminUpdateUser but not generic updateUser.
            // I'll assume I can use api.request or add it.
            // I'll use api.request for now to act quickly, or better, add updateUser to api.ts?
            // "api" object is instance of ApiClient. I can call api['request'] if public? It is private.
            // I'll assume I need to add `updateProfile` to api.ts or just fetch directly.
            // I'll use fetch directly for this specialized case or just add it to api.ts in a subsequent step?
            // Actually, I can use the existing `adminUpdateUser` IF the backend allows it for self?
            // Backend `update_user_admin` is `/api/admin/users/{id}` (Admin only).
            // Backend `update_user_profile` is `/api/users/{id}`.
            // I need to add `updateUserProfile` to api.ts.

            // Wait, I can't restart the `api.ts` edit easily.
            // I'll implement `updateUserProfile` inside this component temporarily using raw fetch/api internal?
            // No, `api` is imported. I can extend it? No.
            // I'll just use `api.adminUpdateUser`? No, that hits admin endpoint.
            // I will use `fetch` with `api.getToken()`? `api` doesn't expose getToken.
            // `useAuth` exposes `token`.

            // I'll use raw fetch here.
            const response = await fetch('/api/users/' + user?.id, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${localStorage.getItem('noslop_token')}`
                },
                body: JSON.stringify(updates)
            });

            if (!response.ok) {
                const err = await response.text();
                throw new Error('Update failed: ' + err);
            }

            const updatedUser = await response.json();

            // Update local context?
            // We have to reload user data. AuthContext doesn't expose a 'reload' method easily without full login logic.
            // But we can manually update localStorage 'noslop_user' and refresh?
            localStorage.setItem('noslop_user', JSON.stringify(updatedUser));
            window.location.reload(); // Simplest way to refresh context

            setMessage({ type: 'success', text: 'Profile updated successfully!' });

        } catch (err: any) {
            setMessage({ type: 'error', text: err.message });
        } finally {
            setIsLoading(false);
        }
    };

    if (!user) return null;

    return (
        <div className="min-h-screen bg-gray-900 text-white p-6 flex justify-center">
            <div className="w-full max-w-2xl bg-gray-800 rounded-lg p-8 shadow-xl">
                <h1 className="text-3xl font-bold mb-8 text-blue-400">Your Profile</h1>

                {message && (
                    <div className={`p-4 rounded mb-6 ${message.type === 'success' ? 'bg-green-900 text-green-200' : 'bg-red-900 text-red-200'}`}>
                        {message.text}
                    </div>
                )}

                <div className="flex items-center gap-4 mb-8 pb-8 border-b border-gray-700">
                    <div className="w-20 h-20 bg-blue-600 rounded-full flex items-center justify-center text-3xl font-bold">
                        {user.username.charAt(0).toUpperCase()}
                    </div>
                    <div>
                        <h2 className="text-2xl font-semibold">{user.username}</h2>
                        <div className="flex gap-2 mt-2">
                            <span className={`px-2 py-1 rounded text-xs ${user.role === 'admin' ? 'bg-purple-900 text-purple-200' : 'bg-gray-700 text-gray-200'}`}>
                                {user.role || 'Basic'}
                            </span>
                            <span className="px-2 py-1 rounded text-xs bg-gray-700 text-gray-300">
                                {user.is_active ? 'Active' : 'Inactive'}
                            </span>
                        </div>
                    </div>
                </div>

                <form onSubmit={handleSubmit} className="space-y-6">
                    <div>
                        <label className="block text-gray-400 mb-2">Email Address</label>
                        <input
                            type="email"
                            value={formData.email}
                            onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                            className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                        />
                    </div>

                    <div>
                        <label className="block text-gray-400 mb-2">Bio</label>
                        <textarea
                            value={formData.bio}
                            onChange={(e) => setFormData({ ...formData, bio: e.target.value })}
                            className="w-full bg-gray-700 rounded p-3 text-white h-32 focus:ring-2 focus:ring-blue-500 outline-none"
                            placeholder="Tell us about yourself..."
                        />
                    </div>

                    <div>
                        <label className="block text-gray-400 mb-2">Custom Data (JSON)</label>
                        <textarea
                            value={formData.custom_data}
                            onChange={(e) => setFormData({ ...formData, custom_data: e.target.value })}
                            className="w-full bg-gray-700 rounded p-3 text-white h-32 font-mono text-sm focus:ring-2 focus:ring-blue-500 outline-none"
                            placeholder='{"key": "value"}'
                        />
                        <p className="text-xs text-gray-500 mt-1">Valid JSON required.</p>
                    </div>

                    <div className="pt-6 flex justify-end">
                        <button
                            type="submit"
                            disabled={isLoading}
                            className="bg-blue-600 hover:bg-blue-700 text-white px-8 py-3 rounded-lg font-medium transition-colors disabled:opacity-50"
                        >
                            {isLoading ? 'Saving...' : 'Save Changes'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}
