
//! START OF FILE frontend/app/components/AgentActivityTerminal.tsx
'use client';

import { useState, useEffect, useRef } from 'react';

interface ActivityMessage {
    type: string;
    data: any;
}

export default function AgentActivityTerminal() {
    const [messages, setMessages] = useState<ActivityMessage[]>([]);
    const [isPaused, setIsPaused] = useState(false);
    const terminalRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        const ws = new WebSocket('ws://localhost:8000/ws/activity');

        ws.onmessage = (event) => {
            const message = JSON.parse(event.data);
            setMessages((prevMessages) => [...prevMessages, message]);
        };

        return () => {
            ws.close();
        };
    }, []);

    useEffect(() => {
        if (!isPaused && terminalRef.current) {
            terminalRef.current.scrollTop = terminalRef.current.scrollHeight;
        }
    }, [messages, isPaused]);

    const handleScroll = () => {
        if (terminalRef.current) {
            const { scrollTop, scrollHeight, clientHeight } = terminalRef.current;
            if (scrollHeight - scrollTop > clientHeight + 5) { // User has scrolled up
                setIsPaused(true);
                setTimeout(() => setIsPaused(false), 10000); // Pause for 10 seconds
            }
        }
    };

    const groupMessagesByProject = () => {
        const grouped: { [key: string]: ActivityMessage[] } = {};
        messages.forEach((msg) => {
            const projectId = msg.data.project_id || msg.data.id;
            if (!grouped[projectId]) {
                grouped[projectId] = [];
            }
            grouped[projectId].push(msg);
        });
        return grouped;
    };

    return (
        <div
            ref={terminalRef}
            onScroll={handleScroll}
            className="agent-activity-terminal h-96 bg-black text-white font-mono text-sm rounded-lg p-4 overflow-y-auto"
        >
            {Object.entries(groupMessagesByProject()).map(([projectId, projectMessages]) => (
                <details key={projectId} open>
                    <summary className="cursor-pointer">Project: {projectId}</summary>
                    <div className="pl-4 border-l-2 border-gray-700">
                        {projectMessages.map((msg, index) => (
                            <div key={index} className="py-1">
                                <span className="text-green-400">{msg.type}:</span> {JSON.stringify(msg.data)}
                            </div>
                        ))}
                    </div>
                </details>
            ))}
        </div>
    );
}
