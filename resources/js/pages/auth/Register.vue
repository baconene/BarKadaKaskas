<script setup lang="ts">
import AuthLayout from '@/layouts/AuthLayout.vue';
import { Head, Link, useForm } from '@inertiajs/vue3';
import { LoaderCircle } from 'lucide-vue-next';

const form = useForm({
    name: '',
    email: '',
    password: '',
    password_confirmation: '',
});

const submit = () => {
    form.post(route('register'), {
        onFinish: () => form.reset('password', 'password_confirmation'),
    });
};
</script>

<template>
    <AuthLayout eyebrow="Join the barkada" title="Create your account" description="Plan trips together and split every expense fairly.">
        <Head title="Register" />

        <form @submit.prevent="submit" class="auth-form">
            <div class="field">
                <label for="name">Full name</label>
                <input
                    id="name"
                    v-model="form.name"
                    type="text"
                    required
                    autofocus
                    autocomplete="name"
                    placeholder="Juan Dela Cruz"
                    :aria-invalid="!!form.errors.name"
                    aria-describedby="name-error"
                />
                <p v-if="form.errors.name" id="name-error" class="field-error">{{ form.errors.name }}</p>
            </div>

            <div class="field">
                <label for="email">Email address</label>
                <input
                    id="email"
                    v-model="form.email"
                    type="email"
                    required
                    autocomplete="email"
                    placeholder="email@example.com"
                    :aria-invalid="!!form.errors.email"
                    aria-describedby="email-error"
                />
                <p v-if="form.errors.email" id="email-error" class="field-error">{{ form.errors.email }}</p>
            </div>

            <div class="form-grid">
                <div class="field">
                    <label for="password">Password</label>
                    <input
                        id="password"
                        v-model="form.password"
                        type="password"
                        required
                        autocomplete="new-password"
                        placeholder="Password"
                        :aria-invalid="!!form.errors.password"
                        aria-describedby="password-error"
                    />
                </div>
                <div class="field">
                    <label for="password_confirmation">Confirm password</label>
                    <input
                        id="password_confirmation"
                        v-model="form.password_confirmation"
                        type="password"
                        required
                        autocomplete="new-password"
                        placeholder="Repeat password"
                        :aria-invalid="!!form.errors.password_confirmation"
                        aria-describedby="password-error"
                    />
                </div>
                <p v-if="form.errors.password || form.errors.password_confirmation" id="password-error" class="field-error full">
                    {{ form.errors.password || form.errors.password_confirmation }}
                </p>
            </div>

            <button type="submit" class="btn auth-submit" :disabled="form.processing">
                <LoaderCircle v-if="form.processing" class="h-4 w-4 animate-spin" />
                Create account
            </button>

            <p class="auth-switch">Already have an account? <Link :href="route('login')" class="auth-link">Log in</Link></p>
        </form>
    </AuthLayout>
</template>
