import SwiftUI
import PhotosUI
import UIKit

/// Tumblr-style photo feed for the trip. Everyone on the trip can post photos with a caption and leave hearts.
struct PhotosView: View {
    let detail: TripDetail
    @EnvironmentObject var store: AppStore
    @State private var posts: [TripPost] = []
    @State private var nextBefore: Int?
    @State private var loaded = false
    @State private var loading = false
    @State private var picks: [PhotosPickerItem] = []
    @State private var composing: [UIImage] = []
    @State private var viewing: Gallery?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Photos").font(.largeTitle.bold())
                    Text("Moments shared by the barkada").foregroundStyle(.secondary)
                }
                if loaded && posts.isEmpty {
                    EmptyMessage(title: "No photos yet", subtitle: "Be the first to share a moment from the trip.", icon: "camera").cardStyle()
                }
                if !loaded && loading { ProgressView().frame(maxWidth: .infinity) }
                ForEach($posts) { $post in
                    PostCard(post: $post, tripID: detail.trip.id, onOpen: { url in viewing = Gallery(urls: post.photos.map(\.url), start: post.photos.firstIndex { $0.url == url } ?? 0) }, onDelete: { delete(post) })
                }
                if nextBefore != nil {
                    Button(loading ? "Loading…" : "Load more") { Task { await load(more: true) } }.buttonStyle(.bordered).frame(maxWidth: .infinity).disabled(loading)
                }
            }
            .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 90)
        }
        .background(Theme.canvas)
        .refreshable { await load() }
        .task { if !loaded { await load() } }
        .safeAreaInset(edge: .bottom) {
            PhotosPicker(selection: $picks, maxSelectionCount: 10, matching: .images) {
                Label("Post photos", systemImage: "photo.badge.plus").font(.headline).padding(.horizontal, 22).padding(.vertical, 15)
                    .foregroundStyle(.white).background(Theme.forest, in: Capsule()).shadow(color: Theme.forest.opacity(0.35), radius: 10, y: 5)
            }
            .frame(maxWidth: .infinity, alignment: .trailing).padding(.horizontal, 16).padding(.bottom, 8)
        }
        .onChange(of: picks) { _, items in
            guard !items.isEmpty else { return }
            Task {
                var images: [UIImage] = []
                for item in items { if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) { images.append(image) } }
                picks = []
                if images.isEmpty { store.error = "Those photos couldn’t be opened." } else { composing = images }
            }
        }
        .sheet(isPresented: Binding(get: { !composing.isEmpty }, set: { if !$0 { composing = [] } })) {
            PostComposer(tripID: detail.trip.id, images: composing) { post in posts.insert(post, at: 0) }
        }
        .fullScreenCover(item: $viewing) { gallery in PhotoViewer(gallery: gallery) }
    }

    private func load(more: Bool = false) async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        do {
            let page = try await store.repository.posts(tripID: detail.trip.id, before: more ? nextBefore : nil)
            posts = more ? posts + page.data : page.data
            nextBefore = page.next_before
            loaded = true
        } catch { store.error = error.localizedDescription }
    }

    private func delete(_ post: TripPost) {
        Task {
            if await store.perform({ try await store.repository.deletePost(tripID: detail.trip.id, postID: post.id) }) {
                posts.removeAll { $0.id == post.id }
            }
        }
    }
}

/// The photos of one post, opened at the tapped one.
struct Gallery: Identifiable { let urls: [String]; let start: Int; var id: String { urls.joined() + "#\(start)" } }

/// Swipeable full-screen gallery with a counter and page dots.
private struct PhotoViewer: View {
    let gallery: Gallery
    @Environment(\.dismiss) private var dismiss
    @State private var page = 0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            TabView(selection: $page) {
                ForEach(Array(gallery.urls.enumerated()), id: \.offset) { index, url in
                    AsyncImage(url: URL(string: url)) { image in image.resizable().scaledToFit() } placeholder: { ProgressView().tint(.white) }
                        .accessibilityLabel("Photo \(index + 1) of \(gallery.urls.count)")
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: gallery.urls.count > 1 ? .always : .never))
            .indexViewStyle(.page(backgroundDisplayMode: .interactive))
            .ignoresSafeArea()
        }
        .overlay(alignment: .top) {
            if gallery.urls.count > 1 {
                Text("\(page + 1) / \(gallery.urls.count)").font(.footnote.bold()).foregroundStyle(.white)
                    .padding(.horizontal, 12).padding(.vertical, 5).background(.black.opacity(0.55), in: Capsule()).padding(.top, 8)
            }
        }
        .overlay(alignment: .topTrailing) {
            Button { dismiss() } label: {
                Image(systemName: "xmark").font(.headline).foregroundStyle(.white).frame(width: 40, height: 40).background(.black.opacity(0.55), in: Circle())
            }
            .padding(12).accessibilityLabel("Close photo")
        }
        .onAppear { page = gallery.start }
    }
}

private struct PostCard: View {
    @Binding var post: TripPost
    let tripID: Int
    let onOpen: (String) -> Void
    let onDelete: () -> Void
    @EnvironmentObject var store: AppStore
    @State private var confirmDelete = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                AvatarView(name: post.author.name, url: post.author.avatar_url, size: 38)
                VStack(alignment: .leading, spacing: 1) {
                    Text(post.author.name).font(.subheadline.bold())
                    Text(TravelFormat.parse(post.created_at), style: .relative).font(.caption).foregroundStyle(.secondary) + Text(" ago").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if post.can_delete {
                    Button { confirmDelete = true } label: { Image(systemName: "trash").foregroundStyle(.secondary) }.accessibilityLabel("Delete post")
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            PhotoGrid(photos: post.photos, onOpen: onOpen)
            if let caption = post.caption, !caption.isEmpty {
                Text(caption).font(.body).padding(.horizontal, 16).padding(.top, 10)
            }
            HStack(spacing: 6) {
                Button { toggleLike() } label: {
                    Image(systemName: post.liked ? "heart.fill" : "heart").font(.title3).foregroundStyle(post.liked ? Color.red : Color.secondary)
                        .frame(width: 44, height: 44)
                }
                .sensoryFeedback(.impact(weight: .light), trigger: post.liked)
                .accessibilityLabel(post.liked ? "Remove heart" : "Add heart")
                Text("\(post.likes_count) \(post.likes_count == 1 ? "heart" : "hearts")").font(.caption).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 6).padding(.vertical, 4)
        }
        .background(.white, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.line, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .confirmationDialog("Delete this post?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete post", role: .destructive, action: onDelete)
        } message: { Text("Its photos are removed for everyone.") }
    }

    /// Updates instantly, then reconciles with the server's count.
    private func toggleLike() {
        let before = post
        post.liked.toggle(); post.likes_count += post.liked ? 1 : -1
        Task {
            do { let result = try await store.repository.toggleLike(tripID: tripID, postID: before.id); post.liked = result.liked; post.likes_count = result.count }
            catch { post.liked = before.liked; post.likes_count = before.likes_count }
        }
    }
}

private struct PhotoGrid: View {
    let photos: [TripPost.Photo]
    let onOpen: (String) -> Void

    var body: some View {
        let shown = Array(photos.prefix(4))
        Group {
            switch shown.count {
            case 0: EmptyView()
            case 1:
                tile(shown[0]).aspectRatio(ratio(shown[0]), contentMode: .fit)
            case 2:
                HStack(spacing: 3) { tile(shown[0]); tile(shown[1]) }.aspectRatio(2, contentMode: .fit)
            case 3:
                HStack(spacing: 3) { tile(shown[0]); VStack(spacing: 3) { tile(shown[1]); tile(shown[2]) } }.aspectRatio(1.5, contentMode: .fit)
            default:
                VStack(spacing: 3) {
                    HStack(spacing: 3) { tile(shown[0]); tile(shown[1]) }
                    HStack(spacing: 3) { tile(shown[2]); tile(shown[3], extra: photos.count - 4) }
                }.aspectRatio(1, contentMode: .fit)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func ratio(_ photo: TripPost.Photo) -> CGFloat {
        guard let w = photo.width, let h = photo.height, h > 0 else { return 4 / 3 }
        return min(max(CGFloat(w) / CGFloat(h), 0.6), 1.9)
    }

    private func tile(_ photo: TripPost.Photo, extra: Int = 0) -> some View {
        Button { onOpen(photo.url) } label: {
            Theme.sage
                .overlay { AsyncImage(url: URL(string: photo.url)) { image in image.resizable().scaledToFill() } placeholder: { ProgressView() } }
                .overlay { if extra > 0 { ZStack { Color.black.opacity(0.45); Text("+\(extra)").font(.title.bold()).foregroundStyle(.white) } } }
                .clipped()
        }
        .buttonStyle(.plain)
    }
}

private struct PostComposer: View {
    let tripID: Int
    let images: [UIImage]
    let onPosted: (TripPost) -> Void
    @EnvironmentObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var caption = ""
    @State private var removed: Set<Int> = []

    private var kept: [UIImage] { images.enumerated().filter { !removed.contains($0.offset) }.map(\.element) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(Array(images.enumerated()), id: \.offset) { index, image in
                                if !removed.contains(index) {
                                    Image(uiImage: image).resizable().scaledToFill().frame(width: 96, height: 96).clipShape(RoundedRectangle(cornerRadius: 12))
                                        .overlay(alignment: .topTrailing) {
                                            Button { removed.insert(index) } label: { Image(systemName: "xmark.circle.fill").font(.title3).foregroundStyle(.white, .black.opacity(0.6)) }.padding(4)
                                        }
                                }
                            }
                        }
                    }
                } footer: { Text("Everyone on the trip can see this post.") }
                Section("Caption") { TextField("Say something about these moments…", text: $caption, axis: .vertical).lineLimit(3...6) }
            }
            .navigationTitle("Post \(kept.count) photo\(kept.count == 1 ? "" : "s")").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Post") {
                        Task {
                            let jpegs = kept.compactMap { $0.jpegData(compressionQuality: 0.95).flatMap { CoverPhoto.jpeg(from: $0, maxSide: 2000) } }
                            var created: TripPost?
                            let ok = await store.perform { created = try await store.repository.createPost(tripID: tripID, caption: caption, jpegs: jpegs) }
                            if ok, let created { onPosted(created); dismiss() }
                        }
                    }.disabled(kept.isEmpty || store.busy)
                }
            }
        }
    }
}
