// START OF FILE frontend/app/context/AuthContext.tsx
"use client";

import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import api from '../../utils/api';

interface User {
    id: string;
    username: string;
    email?: string;
    personality?: any;
    preferences?: any;
}

interface AuthContextType {
    user: User | null;
    token: string | null;
    isAuthenticated: boolean;
    isLoading: boolean;
    login: (username: string, password: string) => Promise<void>;
    register: (data: any) => Promise<void>;
    logout: () => void;
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
        }

        if (storedUser) {
            try {
                setUser(JSON.parse(storedUser));
            } catch (e) {
                console.error("Failed to parse stored user", e);
            }
        }

        setIsLoading(false);
    }, []);

    const login = async (username: string, password: string) => {
        try {
            const data = await api.login(username, password);
            const accessToken = data.access_token;

            setToken(accessToken);
            api.setToken(accessToken);
            localStorage.setItem('noslop_token', accessToken);

            // For now, we construct a basic user object since the login endpoint only returns token
            // In a real app, we might want to fetch user profile after login
            // Or decode the JWT to get username
            const basicUser = { id: 'temp', username: username };
            setUser(basicUser);
            localStorage.setItem('noslop_user', JSON.stringify(basicUser));

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
            throw error;
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

    return (
        <AuthContext.Provider value={{
            user,
            token,
            isAuthenticated: !!token,
            isLoading,
            login,
            register,
            logout
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
