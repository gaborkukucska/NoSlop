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

        // Use centralized WebSocket URL logic
        const wsUrl = api.getWebSocketUrl();

        let ws: WebSocket | null = null;
        let retryTimeout: NodeJS.Timeout;

        const connect = () => {
            // Append /ws/activity to the base WS URL from api.ts if it's not already there?
            // api.getWebSocketUrl() returns base URL (e.g. ws://host:8000).
            // We need to add /ws/activity.

            // Wait, I implemented getWebSocketUrl in api.ts to return base URL.
            // Let's check api.ts implementation.
            // It returns `wss://${host}` or `ws://localhost:8000`.
            // So we need to append /ws/activity.

            const fullWsUrl = `${wsUrl}/ws/activity`;
            ws = new WebSocket(fullWsUrl);

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
