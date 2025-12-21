//! START OF FILE frontend/app/components/ChatInterface.tsx
'use client';

import { useState, useRef, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';
import { useAuth } from '../context/AuthContext';
import api from '../../utils/api';

interface Message {
    role: 'user' | 'assistant';
    content: string;
    timestamp: string;
}

interface Session {
    id: string;
    title: string;
    updated_at: string;
}

interface ChatInterfaceProps {
    initialSessionId?: string;
    refreshTrigger?: number;
}

export default function ChatInterface({ initialSessionId = 'default', refreshTrigger = 0 }: ChatInterfaceProps) {
    const { token } = useAuth();
    const [sessionId, setSessionId] = useState(initialSessionId);
    const [sessions, setSessions] = useState<Session[]>([]);
    const [messages, setMessages] = useState<Message[]>([]);
    const [input, setInput] = useState('');
    const [isLoading, setIsLoading] = useState(false);
    const [isPriming, setIsPriming] = useState(false);
    const [suggestions, setSuggestions] = useState<string[]>([]);
    const [isSidebarOpen, setIsSidebarOpen] = useState(true);
    const [isListening, setIsListening] = useState(false);
    const [isSpeaking, setIsSpeaking] = useState(false);
    const [ttsEnabled, setTtsEnabled] = useState(false);
    const [isSpeechAvailable, setIsSpeechAvailable] = useState(false);

    const messagesEndRef = useRef<HTMLDivElement>(null);
    const recognitionRef = useRef<any>(null);

    const scrollToBottom = () => {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    };

    useEffect(() => {
        scrollToBottom();
    }, [messages, isPriming]);

    // Load sessions
    const loadSessions = async () => {
        try {
            const data = await api.getSessions();
            if (data.sessions) {
                setSessions(data.sessions);
                // Always create a new session on initial load (default state)
                // This ensures we get a fresh "Daily Briefing" / Priming sequence
                if (sessionId === 'default') {
                    createNewSession();
                }
            }
        } catch (error) {
            console.error('Failed to load sessions:', error);
        }
    };

    useEffect(() => {
        loadSessions();
    }, []);

    // Load chat history when sessionId changes or refreshTrigger updates
    useEffect(() => {
        const loadHistory = async () => {
            setIsLoading(true);
            try {
                const data = await api.getChatHistory(sessionId);
                if (data.history) {
                    setMessages(data.history.map((msg: any) => ({
                        role: msg.role,
                        content: msg.content,
                        timestamp: msg.timestamp || new Date().toISOString()
                    })));
                } else {
                    setMessages([]);
                }
            } catch (error) {
                console.error('Failed to load chat history:', error);
                setMessages([]);
            } finally {
                setIsLoading(false);
            }
        };

        loadHistory();
    }, [sessionId, refreshTrigger]);

    // Audio Recording State
    const [mediaRecorder, setMediaRecorder] = useState<MediaRecorder | null>(null);
    const audioChunksRef = useRef<Blob[]>([]);

    // Initialize (No browser speech init needed anymore)
    useEffect(() => {
        // Just verify media devices support
        if (navigator.mediaDevices) {
            setIsSpeechAvailable(true);
        }
    }, []);

    const startRecording = async () => {
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            const recorder = new MediaRecorder(stream);

            recorder.ondataavailable = (e) => {
                if (e.data.size > 0) {
                    audioChunksRef.current.push(e.data);
                }
            };

            recorder.onstop = async () => {
                const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/wav' });
                audioChunksRef.current = []; // Reset chunks

                // Transcribe
                setIsLoading(true);
                try {
                    const data = await api.transcribe(audioBlob);
                    if (data.text) {
                        setInput((prev) => prev + (prev ? ' ' : '') + data.text);
                    }
                } catch (error) {
                    console.error('Transcription error:', error);
                } finally {
                    setIsLoading(false);
                }

                // Stop tracks
                stream.getTracks().forEach(track => track.stop());
            };

            recorder.start();
            setMediaRecorder(recorder);
            setIsListening(true);

        } catch (error) {
            console.error('Error accessing microphone:', error);
            alert('Could not access microphone.');
        }
    };

    const stopRecording = () => {
        if (mediaRecorder && mediaRecorder.state !== 'inactive') {
            mediaRecorder.stop();
            setIsListening(false);
            setMediaRecorder(null);
        }
    };

    const toggleListening = () => {
        if (isListening) {
            stopRecording();
        } else {
            startRecording();
        }
    };

    const speak = async (text: string) => {
        if (!ttsEnabled) return;

        setIsSpeaking(true);

        try {
            const blob = await api.speak(text);
            const url = URL.createObjectURL(blob);
            const audio = new Audio(url);

            audio.onended = () => {
                setIsSpeaking(false);
                URL.revokeObjectURL(url);
            };

            audio.onerror = () => {
                setIsSpeaking(false);
            };

            audio.play();
        } catch (error) {
            console.error("TTS error:", error);
            setIsSpeaking(false);
        }
    };



    const createNewSession = async () => {
        setIsPriming(true);
        try {
            const newSession = await api.createSession();
            setSessions((prev) => [newSession, ...prev]);
            setSessionId(newSession.id);
            setMessages([]);
            if (window.innerWidth < 768) setIsSidebarOpen(false);

            // Prime the new session immediately so Admin AI introduces itself
            try {
                // Await this to keep overlay visible during generation
                await api.primeAdminAI(newSession.id);

                // Fetch updated history containing the prime message
                try {
                    const data = await api.getChatHistory(newSession.id);
                    if (data.history && data.history.length > 0) {
                        setMessages(data.history.map((msg: any) => ({
                            role: msg.role,
                            content: msg.content,
                            timestamp: msg.timestamp || new Date().toISOString()
                        })));
                    }
                } catch (historyError) {
                    console.error("Failed to fetch history after priming:", historyError);
                }
            } catch (e) {
                console.error("Failed to prime new session:", e);
            }

        } catch (error) {
            console.error('Failed to create session:', error);
        } finally {
            setIsPriming(false);
        }
    };

    const deleteSession = async (id: string, e: React.MouseEvent) => {
        e.stopPropagation();
        if (!confirm('Are you sure you want to delete this chat?')) return;

        try {
            await api.deleteSession(id);
            setSessions((prev) => prev.filter(s => s.id !== id));
            if (sessionId === id) {
                setSessionId('default'); // Fallback to default or create new
            }
        } catch (error) {
            console.error('Failed to delete session:', error);
        }
    };

    const sendMessage = async (messageText?: string) => {
        const textToSend = messageText || input;
        if (!textToSend.trim()) return;

        const userMessage: Message = {
            role: 'user',
            content: textToSend,
            timestamp: new Date().toISOString(),
        };

        setMessages((prev) => [...prev, userMessage]);
        setInput('');
        setIsLoading(true);

        try {
            // Updated to use centralized api.sendMessage which handles URLs correctly
            const data = await api.sendMessage(textToSend, sessionId);

            const aiMessage: Message = {
                role: 'assistant',
                content: data.message,
                timestamp: new Date().toISOString(),
            };

            setMessages((prev) => [...prev, aiMessage]);

            // Speak response if TTS enabled
            if (ttsEnabled) {
                speak(data.message);
            }

            if (data.suggestions) {
                setSuggestions(data.suggestions);
            }

            // Refresh sessions list to update titles/timestamps
            loadSessions();

        } catch (error) {
            console.error('Chat error:', error);
            const errorMessage: Message = {
                role: 'assistant',
                content: 'Sorry, I encountered an error. Please make sure the backend is running.',
                timestamp: new Date().toISOString(),
            };
            setMessages((prev) => [...prev, errorMessage]);
        } finally {
            setIsLoading(false);
        }
    };

    const handleKeyPress = (e: React.KeyboardEvent) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    };

    const clearHistory = async () => {
        try {
            // Use api.ts for clearing history
            await api.clearChatHistory(sessionId);
            setMessages([]);
            setSuggestions([]);
            // Refresh sessions to update the current session's title/timestamp if it was cleared
            loadSessions();
        } catch (error) {
            console.error('Error clearing history:', error);
        }
    };

    return (
        <div className="flex h-full bg-gray-900 text-white overflow-hidden rounded-lg">
            {/* Sidebar */}
            <div
                className={`${isSidebarOpen ? 'w-64' : 'w-0'} transition-all duration-300 bg-gray-800 border-r border-gray-700 flex flex-col overflow-hidden`}
            >
                <div className="p-4 border-b border-gray-700 flex justify-between items-center">
                    <h2 className="font-semibold">Chats</h2>
                    <button
                        onClick={createNewSession}
                        className="p-2 bg-blue-600 rounded hover:bg-blue-700 transition-colors"
                        title="New Chat"
                    >
                        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 4v16m8-8H4"></path></svg>
                    </button>
                </div>

                <div className="flex-1 overflow-y-auto p-2 space-y-2">
                    {sessions.map((session) => (
                        <div
                            key={session.id}
                            onClick={() => setSessionId(session.id)}
                            className={`p-3 rounded cursor-pointer group flex justify-between items-center ${sessionId === session.id ? 'bg-gray-700' : 'hover:bg-gray-750'}`}
                        >
                            <div className="truncate text-sm flex-1 pr-2">
                                {session.title || 'New Chat'}
                            </div>
                            <button
                                onClick={(e) => deleteSession(session.id, e)}
                                className="opacity-0 group-hover:opacity-100 text-gray-400 hover:text-red-400 p-1"
                            >
                                <svg className="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12"></path></svg>
                            </button>
                        </div>
                    ))}
                </div>
            </div>

            {/* Main Chat Area */}
            <div className="flex-1 flex flex-col min-w-0">
                {/* Header */}
                <div className="h-14 border-b border-gray-700 flex items-center px-4 justify-between bg-gray-800/50 backdrop-blur">
                    <div className="flex items-center gap-3">
                        <button
                            onClick={() => setIsSidebarOpen(!isSidebarOpen)}
                            className="text-gray-400 hover:text-white"
                        >
                            <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 6h16M4 12h16M4 18h16"></path></svg>
                        </button>
                        <span className="font-medium truncate">
                            {sessions.find(s => s.id === sessionId)?.title || 'Admin AI'}
                        </span>
                    </div>

                    <div className="flex items-center gap-2">
                        <button
                            onClick={() => setTtsEnabled(!ttsEnabled)}
                            className={`p-2 rounded-full transition-colors ${ttsEnabled ? 'bg-blue-600/20 text-blue-400' : 'text-gray-400 hover:bg-gray-700'}`}
                            title={ttsEnabled ? "Disable TTS" : "Enable TTS"}
                        >
                            {isSpeaking ? (
                                <span className="animate-pulse">🔊</span>
                            ) : (
                                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M15.536 8.464a5 5 0 010 7.072m2.828-9.9a9 9 0 010 12.728M5.586 15H4a1 1 0 01-1-1v-4a1 1 0 011-1h1.586l4.707-4.707C10.923 3.663 12 4.109 12 5v14c0 .891-1.077 1.337-1.707.707L5.586 15z"></path></svg>
                            )}
                        </button>
                        <button
                            onClick={clearHistory}
                            className="px-3 py-1 text-sm text-gray-400 hover:text-white transition-colors"
                        >
                            Clear Chat
                        </button>
                    </div>
                </div>

                {/* Messages */}
                <div className="flex-1 overflow-y-auto p-4 space-y-4 relative">
                    {/* Priming Overlay */}
                    {isPriming && (
                        <div className="absolute inset-0 z-10 flex flex-col items-center justify-center bg-zinc-50 dark:bg-zinc-900/90 backdrop-blur-sm">
                            <div className="flex flex-col items-center space-y-4 animate-pulse">
                                <div className="relative">
                                    <div className="absolute inset-0 bg-blue-500 rounded-full blur-xl opacity-20 animate-pulse"></div>
                                    <svg className="relative w-16 h-16 text-blue-600 dark:text-blue-400 animate-spin-slow" fill="none" viewBox="0 0 24 24">
                                        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                                        <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                                    </svg>
                                </div>
                                <h2 className="text-xl font-bold text-zinc-800 dark:text-zinc-100 mt-4">ESTABLISHING NEURAL LINK</h2>
                                <p className="text-zinc-500 dark:text-zinc-400">Calibrating Admin AI Personality...</p>
                            </div>
                        </div>
                    )}

                    {!isPriming && messages.length === 0 && (
                        <div className="text-center text-gray-500 mt-8">
                            <p className="text-lg mb-2">👋 Hello! I'm your Admin AI.</p>
                            <p className="text-sm">How can I help you create amazing content today?</p>
                        </div>
                    )}
                    {messages.map((msg, idx) => (
                        <div
                            key={idx}
                            className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
                        >
                            <div
                                className={`max-w-[80%] rounded-lg p-4 ${msg.role === 'user'
                                    ? 'bg-blue-600 text-white'
                                    : 'bg-gray-700 text-gray-100'
                                    }`}
                            >
                                <div className="prose prose-invert max-w-none">
                                    <ReactMarkdown>
                                        {msg.content}
                                    </ReactMarkdown>
                                </div>
                                <div className="text-xs opacity-50 mt-2">
                                    {new Date(msg.timestamp).toLocaleTimeString()}
                                </div>
                            </div>
                        </div>
                    ))}
                    {isLoading && (
                        <div className="flex justify-start">
                            <div className="bg-gray-700 rounded-lg p-4 text-gray-400 animate-pulse">
                                Thinking...
                            </div>
                        </div>
                    )}
                    <div ref={messagesEndRef} />
                </div>

                {/* Input Area */}
                <div className="p-4 border-t border-gray-700 bg-gray-800">
                    {suggestions.length > 0 && (
                        <div className="flex gap-2 mb-4 overflow-x-auto pb-2">
                            {suggestions.map((suggestion, idx) => (
                                <button
                                    key={idx}
                                    onClick={() => sendMessage(suggestion)}
                                    className="whitespace-nowrap px-3 py-1 bg-gray-700 hover:bg-gray-600 rounded-full text-sm text-gray-300 transition-colors"
                                >
                                    {suggestion}
                                </button>
                            ))}
                        </div>
                    )}

                    <div className="flex gap-2">
                        {isSpeechAvailable ? (
                            <button
                                onClick={toggleListening}
                                className={`p-3 rounded-lg transition-colors ${isListening ? 'bg-red-600 text-white animate-pulse' : 'bg-gray-700 text-gray-400 hover:bg-gray-600'}`}
                                title="Voice Input"
                            >
                                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z"></path></svg>
                            </button>
                        ) : (
                            <button
                                disabled
                                className="p-3 rounded-lg bg-gray-700 text-gray-600 cursor-not-allowed opacity-50"
                                title="Voice input requires HTTPS or localhost"
                            >
                                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z"></path></svg>
                            </button>
                        )}

                        <input
                            type="text"
                            value={input}
                            onChange={(e) => setInput(e.target.value)}
                            onKeyPress={handleKeyPress}
                            placeholder="Type a message..."
                            className="flex-1 bg-gray-700 text-white rounded-lg px-4 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
                            disabled={isLoading}
                        />
                        <button
                            onClick={() => sendMessage()}
                            disabled={isLoading || !input.trim()}
                            className="bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                        >
                            Send
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}
