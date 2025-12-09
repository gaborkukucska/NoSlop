// START OF FILE frontend/app/register/page.tsx
"use client";

import React, { useState } from 'react';
import { useAuth } from '../context/AuthContext';
import Link from 'next/link';

export default function RegisterPage() {
    const [username, setUsername] = useState('');
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState('');
    const { register } = useAuth();

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError('');
        try {
            await register({ username, email, password });
        } catch (err: any) {
            setError(err.message || 'Registration failed');
        }
    };

    return (
        <div className="min-h-screen flex items-center justify-center bg-gray-900 text-white">
            <div className="bg-gray-800 p-8 rounded-lg shadow-lg w-full max-w-md">
                <h1 className="text-3xl font-bold mb-6 text-center text-green-400">NoSlop Register</h1>

                {error && (
                    <div className="bg-red-500/20 border border-red-500 text-red-200 p-3 rounded mb-4">
                        {error}
                    </div>
                )}

                <form onSubmit={handleSubmit} className="space-y-4">
                    <div>
                        <label htmlFor="username" className="block text-sm font-medium mb-1">Username</label>
                        <input
                            id="username"
                            name="username"
                            type="text"
                            value={username}
                            onChange={(e) => setUsername(e.target.value)}
                            className="w-full bg-gray-700 border border-gray-600 rounded p-2 focus:outline-none focus:border-green-500"
                            required
                        />
                    </div>

                    <div>
                        <label htmlFor="email" className="block text-sm font-medium mb-1">Email (Optional)</label>
                        <input
                            id="email"
                            name="email"
                            type="email"
                            value={email}
                            onChange={(e) => setEmail(e.target.value)}
                            className="w-full bg-gray-700 border border-gray-600 rounded p-2 focus:outline-none focus:border-green-500"
                        />
                    </div>

                    <div>
                        <label htmlFor="password" className="block text-sm font-medium mb-1">Password</label>
                        <input
                            id="password"
                            name="password"
                            type="password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            className="w-full bg-gray-700 border border-gray-600 rounded p-2 focus:outline-none focus:border-green-500"
                            required
                        />
                    </div>

                    <button
                        type="submit"
                        className="w-full bg-green-600 hover:bg-green-700 text-white font-bold py-2 px-4 rounded transition duration-200"
                    >
                        Register
                    </button>
                </form>

                <div className="mt-4 text-center text-sm text-gray-400">
                    Already have an account?{' '}
                    <Link href="/login" className="text-green-400 hover:underline">
                        Login
                    </Link>
                </div>
            </div>
        </div>
    );
}
