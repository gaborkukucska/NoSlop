'use client';

import { useState } from 'react';
import api from '../../utils/api';

interface AvatarUploadProps {
    currentAvatarUrl?: string;
    onUploadSuccess: (avatarUrl: string) => void;
}

export default function AvatarUpload({ currentAvatarUrl, onUploadSuccess }: AvatarUploadProps) {
    const [isUploading, setIsUploading] = useState(false);
    const [preview, setPreview] = useState<string | null>(currentAvatarUrl || null);
    const [error, setError] = useState<string | null>(null);

    const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file) return;

        // Validate file type
        if (!file.type.startsWith('image/')) {
            setError('Please select an image file');
            return;
        }

        // Validate file size (5MB)
        if (file.size > 5 * 1024 * 1024) {
            setError('File size must be less than 5MB');
            return;
        }

        setError(null);
        setIsUploading(true);

        try {
            // Show preview
            const reader = new FileReader();
            reader.onload = (e) => {
                setPreview(e.target?.result as string);
            };
            reader.readAsDataURL(file);

            // Upload to server
            const result = await api.uploadAvatar(file);
            onUploadSuccess(result.avatar_url);
        } catch (err: any) {
            setError(err.message || 'Upload failed');
            setPreview(currentAvatarUrl || null);
        } finally {
            setIsUploading(false);
        }
    };

    const handleDelete = async () => {
        if (!confirm('Delete your avatar?')) return;

        setIsUploading(true);
        try {
            await api.deleteAvatar();
            setPreview(null);
            onUploadSuccess('');
        } catch (err: any) {
            setError(err.message || 'Delete failed');
        } finally {
            setIsUploading(false);
        }
    };

    return (
        <div className="space-y-4">
            <div className="flex items-center gap-4">
                <div className="relative">
                    {preview ? (
                        <img
                            src={preview}
                            alt="Avatar"
                            className="w-32 h-32 rounded-full object-cover border-4 border-gray-700"
                        />
                    ) : (
                        <div className="w-32 h-32 bg-gray-700 rounded-full flex items-center justify-center text-4xl font-bold text-gray-400">
                            ?
                        </div>
                    )}
                    {isUploading && (
                        <div className="absolute inset-0 bg-black bg-opacity-50 rounded-full flex items-center justify-center">
                            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-white"></div>
                        </div>
                    )}
                </div>

                <div className="flex flex-col gap-2">
                    <label className="bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded cursor-pointer text-sm font-medium transition-colors">
                        {preview ? 'Change Avatar' : 'Upload Avatar'}
                        <input
                            type="file"
                            accept="image/*"
                            onChange={handleFileSelect}
                            className="hidden"
                            disabled={isUploading}
                        />
                    </label>

                    {preview && (
                        <button
                            onClick={handleDelete}
                            disabled={isUploading}
                            className="bg-red-600 hover:bg-red-700 text-white px-4 py-2 rounded text-sm font-medium transition-colors disabled:opacity-50"
                        >
                            Remove Avatar
                        </button>
                    )}
                </div>
            </div>

            {error && (
                <div className="bg-red-900 text-red-200 p-3 rounded">
                    {error}
                </div>
            )}

            <p className="text-xs text-gray-500">
                JPG, PNG or WebP. Max 5MB. Square images work best.
            </p>
        </div>
    );
}
