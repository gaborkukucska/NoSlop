// START OF FILE frontend/app/context/AuthContext.tsx
"use client";

import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import api from '../../utils/api';

interface User {
    id: string;
    username: string;
    email?: string;
    role?: string;
    is_active?: boolean;

    // Profile
    bio?: string;
    display_name?: string;
    first_name?: string;
    last_name?: string;
    date_of_birth?: string;
    location?: string;
    address?: string;
    timezone?: string;
    avatar_url?: string;

    // Personalization (for Admin AI)
    interests?: string[];
    occupation?: string;
    experience_level?: string;
    preferred_media_types?: string[];
    content_goals?: string;

    // Social & Privacy
    social_links?: Record<string, string>;
    profile_visibility?: string;

    // Security
    email_verified?: boolean;
    password_changed_at?: string;

    // Legacy fields
    custom_data?: any;
    personality?: any;
    preferences?: any;

    // Timestamps
    created_at?: string;
    updated_at?: string;
    last_login?: string;
}

interface AuthContextType {
    user: User | null;
    token: string | null;
    isAuthenticated: boolean;
    isAdmin: boolean;
    isLoading: boolean;
    login: (username: string, password: string) => Promise<void>;
    register: (data: any) => Promise<void>;
    logout: () => void;
    refreshUser: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider = ({ children }: { children: ReactNode }) => {
    const [user, setUser] = useState<User | null>(null);
    const [token, setToken] = useState<string | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const router = useRouter();

    useEffect(() => {
        // Load token from localStorage on mount
        const storedToken = localStorage.getItem('noslop_token');
        const storedUser = localStorage.getItem('noslop_user');

        if (storedToken) {
            setToken(storedToken);
            api.setToken(storedToken);

            // Register logout callback for 401s
            api.setUnauthorizedCallback(() => {
                logout();
            });

            // Verify token validity by making a lightweight authenticated call
            // We use getSessions as it requires auth and is lightweight
            api.getSessions()
                .then(() => {
                    // Token is valid
                    setIsLoading(false);
                })
                .catch((err) => {
                    console.error("Token verification failed:", err);
                    // If 401, the interceptor will handle logout. 
                    // If other error (network), we might still want to allow access or retry?
                    // For now, if verification fails entirely, we should probably assume invalid session to be safe,
                    // BUT be careful of network errors flaking.

                    // Actually, since we added the interceptor, if it was a 401, logout() already ran.
                    // If it was another error, we probably shouldn't force logout immediately.
                    setIsLoading(false);
                });
        } else {
            setIsLoading(false);
        }

        if (storedUser) {
            try {
                setUser(JSON.parse(storedUser));
            } catch (e) {
                console.error("Failed to parse stored user", e);
            }
        }
    }, []);

    const login = async (username: string, password: string) => {
        try {
            const data = await api.login(username, password);
            const accessToken = data.access_token;
            const userData = data.user;

            setToken(accessToken);
            api.setToken(accessToken);
            localStorage.setItem('noslop_token', accessToken);

            // Use returned user data
            setUser(userData);
            localStorage.setItem('noslop_user', JSON.stringify(userData));

            // Redirect based on role? Or just home
            router.push('/');
        } catch (error) {
            console.error("Login failed", error);
            throw error;
        }
    };

    const register = async (data: any) => {
        try {
            await api.register(data);
            // Auto login after register? Or redirect to login?
            // Let's redirect to login for now
            router.push('/login');
        } catch (error) {
            console.error("Registration failed", error);
            // It's better to show the error to the user
            if (error instanceof Error) {
                throw new Error(error.message || 'Registration failed');
            }
            throw new Error('An unknown error occurred during registration.');
        }
    };

    const logout = () => {
        setUser(null);
        setToken(null);
        api.setToken(null);
        localStorage.removeItem('noslop_token');
        localStorage.removeItem('noslop_user');
        router.push('/login');
    };

    const refreshUser = async () => {
        try {
            if (!token) return;
            const userData = await api.getCurrentUser();
            setUser(userData);
            localStorage.setItem('noslop_user', JSON.stringify(userData));
        } catch (error) {
            console.error('Failed to refresh user data:', error);
        }
    };

    return (
        <AuthContext.Provider value={{
            user,
            token,
            isAuthenticated: !!token,
            isAdmin: user?.role === 'admin',
            isLoading,
            login,
            register,
            logout,
            refreshUser
        }}>
            {children}
        </AuthContext.Provider>
    );
};

export const useAuth = () => {
    const context = useContext(AuthContext);
    if (context === undefined) {
        throw new Error('useAuth must be used within an AuthProvider');
    }
    return context;
};
