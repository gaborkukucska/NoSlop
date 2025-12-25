'use client';

import { useState } from 'react';
import api from '../../utils/api';

export default function PasswordChange() {
    const [isChanging, setIsChanging] = useState(false);
    const [showPasswords, setShowPasswords] = useState(false);
    const [passwords, setPasswords] = useState({
        current: '',
        new: '',
        confirm: ''
    });
    const [message, setMessage] = useState<{ type: 'success' | 'error', text: string } | null>(null);

    const getPasswordStrength = (password: string) => {
        if (password.length < 8) return { strength: 'Too short', color: 'text-red-400' };
        if (password.length >= 12 && /[A-Z]/.test(password) && /[0-9]/.test(password) && /[^A-Za-z0-9]/.test(password)) {
            return { strength: 'Strong', color: 'text-green-400' };
        }
        if (password.length >= 10 && /[A-Z]/.test(password) && /[0-9]/.test(password)) {
            return { strength: 'Good', color: 'text-blue-400' };
        }
        return { strength: 'Weak', color: 'text-yellow-400' };
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setMessage(null);

        // Validation
        if (passwords.new.length < 8) {
            setMessage({ type: 'error', text: 'New password must be at least 8 characters' });
            return;
        }

        if (passwords.new !== passwords.confirm) {
            setMessage({ type: 'error', text: 'New passwords do not match' });
            return;
        }

        setIsChanging(true);

        try {
            await api.changePassword(passwords.current, passwords.new);
            setMessage({ type: 'success', text: 'Password changed successfully!' });
            setPasswords({ current: '', new: '', confirm: '' });
        } catch (err: any) {
            setMessage({ type: 'error', text: err.message || 'Password change failed' });
        } finally {
            setIsChanging(false);
        }
    };

    const passwordStrength = getPasswordStrength(passwords.new);

    return (
        <div className="bg-gray-800 rounded-lg p-6">
            <h3 className="text-xl font-semibold mb-4">Change Password</h3>

            {message && (
                <div className={`p-3 rounded mb-4 ${message.type === 'success' ? 'bg-green-900 text-green-200' : 'bg-red-900 text-red-200'}`}>
                    {message.text}
                </div>
            )}

            <form onSubmit={handleSubmit} className="space-y-4">
                <div>
                    <label className="block text-gray-400 mb-2 text-sm">Current Password</label>
                    <input
                        type={showPasswords ? 'text' : 'password'}
                        value={passwords.current}
                        onChange={(e) => setPasswords({ ...passwords, current: e.target.value })}
                        className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                        required
                    />
                </div>

                <div>
                    <label className="block text-gray-400 mb-2 text-sm">New Password</label>
                    <input
                        type={showPasswords ? 'text' : 'password'}
                        value={passwords.new}
                        onChange={(e) => setPasswords({ ...passwords, new: e.target.value })}
                        className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                        required
                    />
                    {passwords.new && (
                        <p className={`text-xs mt-1 ${passwordStrength.color}`}>
                            Strength: {passwordStrength.strength}
                        </p>
                    )}
                </div>

                <div>
                    <label className="block text-gray-400 mb-2 text-sm">Confirm New Password</label>
                    <input
                        type={showPasswords ? 'text' : 'password'}
                        value={passwords.confirm}
                        onChange={(e) => setPasswords({ ...passwords, confirm: e.target.value })}
                        className="w-full bg-gray-700 rounded p-3 text-white focus:ring-2 focus:ring-blue-500 outline-none"
                        required
                    />
                </div>

                <div className="flex items-center gap-2">
                    <input
                        type="checkbox"
                        id="showPasswords"
                        checked={showPasswords}
                        onChange={(e) => setShowPasswords(e.target.checked)}
                        className="w-4 h-4"
                    />
                    <label htmlFor="showPasswords" className="text-sm text-gray-400 cursor-pointer">
                        Show passwords
                    </label>
                </div>

                <button
                    type="submit"
                    disabled={isChanging}
                    className="w-full bg-blue-600 hover:bg-blue-700 text-white px-4 py-3 rounded font-medium transition-colors disabled:opacity-50"
                >
                    {isChanging ? 'Changing Password...' : 'Change Password'}
                </button>
            </form>
        </div>
    );
}
