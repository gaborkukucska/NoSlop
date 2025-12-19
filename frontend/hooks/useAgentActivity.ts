import { useState, useEffect } from 'react';
import { api } from '../utils/api';
import { useAuth } from '../app/context/AuthContext';

export interface ActivityMessage {
    type: string;
    data: any;
}

export const useAgentActivity = () => {
    const { isAuthenticated, token } = useAuth();
    const [messages, setMessages] = useState<ActivityMessage[]>([]);

    useEffect(() => {
        // Safe check for window availability (Next.js SSR)
        if (typeof window === 'undefined') return;

        // Wait for authentication
        if (!isAuthenticated || !token) return;

        // Determine WebSocket URL
        let wsUrl = '';

        // 1. HTTPS check (Priority for Cloudflare Tunnel)
        if (typeof window !== 'undefined' && window.location.protocol === 'https:') {
            // Use relative path (upgrade current location to WSS)
            // If we are at https://app.noslop.me/, ws will be wss://app.noslop.me/ws/activity
            const host = window.location.host;
            wsUrl = `wss://${host}/ws/activity`;
        }
        // 2. Check API URL environment variable
        else if (process.env.NEXT_PUBLIC_API_URL && process.env.NEXT_PUBLIC_API_URL !== '') {
            const apiUrl = process.env.NEXT_PUBLIC_API_URL;
            wsUrl = apiUrl.replace(/^http/, 'ws') + '/ws/activity';
        }
        // 3. Fallback to dynamic detection from window.location
        else if (typeof window !== 'undefined') {
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const host = window.location.host; // Includes port if present

            // If we are plain HTTP, assume backend is nearby or same host
            // Note: If using port 8000 fallback, we need to be careful.
            // But usually NEXT_PUBLIC_API_URL catches the 8000 port case.
            // If we are here, we are likely purely dynamic.
            if (window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1' && !process.env.NEXT_PUBLIC_API_URL) {
                // Fallback for LAN usage without env var - assume port 8000
                // But we must separate hostname from port if present in window.location.host
                const hostname = window.location.hostname;
                wsUrl = `${protocol}//${hostname}:8000/ws/activity`;
            } else {
                // Localhost or other
                wsUrl = `${protocol}//${host}/ws/activity`;
            }
        }

        // Final fallback (should rarely happen)
        if (!wsUrl) {
            wsUrl = 'ws://localhost:8000/ws/activity';
        }

        let ws: WebSocket | null = null;
        let retryTimeout: NodeJS.Timeout;

        const connect = () => {
            ws = new WebSocket(wsUrl);

            ws.onopen = () => {
                console.log('Connected to Agent Activity WebSocket');
            };

            ws.onmessage = (event) => {
                try {
                    const message = JSON.parse(event.data);
                    setMessages((prevMessages) => [...prevMessages, message]);
                } catch (e) {
                    console.error('Failed to parse activity message:', e);
                }
            };

            ws.onclose = () => {
                console.log('Agent Activity WebSocket disconnected, retrying in 3s...');
                retryTimeout = setTimeout(connect, 3000);
            };

            ws.onerror = (error) => {
                console.error('WebSocket error:', error);
                ws?.close();
            };
        };

        // Fetch history first
        const fetchHistory = async () => {
            try {
                const history = await api.getActivityHistory();
                if (Array.isArray(history)) {
                    setMessages(history);
                }
            } catch (e) {
                console.error('Failed to fetch activity history:', e);
            }
        };

        fetchHistory();
        connect();

        return () => {
            if (ws) {
                ws.onclose = null; // Prevent reconnect on cleanup
                ws.close();
            }
            if (retryTimeout) clearTimeout(retryTimeout);
        };
    }, [isAuthenticated, token]);

    return { messages };
};
