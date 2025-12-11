import { useState, useEffect } from 'react';
import { api } from '../utils/api';

export interface ActivityMessage {
    type: string;
    data: any;
}

export const useAgentActivity = () => {
    const [messages, setMessages] = useState<ActivityMessage[]>([]);

    useEffect(() => {
        // Safe check for window availability (Next.js SSR)
        if (typeof window === 'undefined') return;

        const hostname = window.location.hostname;
        const wsUrl = (hostname !== 'localhost' && hostname !== '127.0.0.1')
            ? `ws://${hostname}:8000/ws/activity`
            : 'ws://localhost:8000/ws/activity';

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
    }, []);

    return { messages };
};
