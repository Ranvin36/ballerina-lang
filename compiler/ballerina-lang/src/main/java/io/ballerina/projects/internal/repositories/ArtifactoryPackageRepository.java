package io.ballerina.projects.internal.repositories;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.ballerina.projects.*;
import io.ballerina.projects.Package;
import io.ballerina.projects.environment.Environment;
import io.ballerina.projects.environment.PackageMetadataResponse;
import io.ballerina.projects.environment.ResolutionOptions;
import io.ballerina.projects.environment.ResolutionRequest;
import io.ballerina.projects.util.FileUtils;
import io.ballerina.projects.util.ProjectUtils;
import org.ballerinalang.artifactory.ArtifactoryClient;
import io.ballerina.projects.internal.model.Repository;
import org.wso2.ballerinalang.util.RepoUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static io.ballerina.projects.util.ProjectConstants.BALA_EXTENSION;


public class ArtifactoryPackageRepository extends AbstractPackageRepository{
    private final ArtifactoryClient artifactoryClient;
    private final FileSystemRepository fileSystemRepository;
    private final String repoLocation;

    private final Path balaDirectory; // added field to track the explicit bala subdirectory

    public static ArtifactoryPackageRepository from(Environment environment, Path repositoryPath, Repository repository){
        if(Files.notExists(repositoryPath)){
            throw new ProjectException("cache directory does not exists: " + repositoryPath);
        }
        // Pass the bala subdirectory to ArtifactoryClient so packages are downloaded to the correct location
        Path balaDirectory = repositoryPath.resolve("bala");
        ArtifactoryClient artifactoryClient = new ArtifactoryClient(repository.url(), repository.username(),
                repository.password(), balaDirectory);
        String ballerinaShortVersion = RepoUtils.getBallerinaShortVersion();
        System.out.println(repositoryPath + " : " + balaDirectory);

        return new ArtifactoryPackageRepository(environment, repositoryPath, ballerinaShortVersion, artifactoryClient, balaDirectory);
    }

    // Existing constructor retained for backward compatibility - uses repositoryPath/bala as default
    public ArtifactoryPackageRepository(Environment environment, Path repositoryPath, String distributionVersion,
                                        ArtifactoryClient artifactoryClient) {
        this(environment, repositoryPath, distributionVersion, artifactoryClient, repositoryPath.resolve("bala"));
    }

    // New constructor that accepts the explicit balaDirectory
    public ArtifactoryPackageRepository(Environment environment, Path repositoryPath, String distributionVersion,
                                        ArtifactoryClient artifactoryClient, Path balaDirectory) {
        this.fileSystemRepository = new FileSystemRepository(environment, repositoryPath, distributionVersion);
        this.artifactoryClient = artifactoryClient;
        this.repoLocation = repositoryPath.toString();
        this.balaDirectory = balaDirectory;
    }


    @Override
    public List<PackageVersion> getPackageVersions(PackageOrg org, PackageName name, PackageVersion version) {
        // If a specific version is requested -> prefer local only
        if (version != null) {
            Path balaPath = this.fileSystemRepository.getPackagePath(org.toString(), name.toString(),
                    version.toString());
            if (Files.exists(balaPath)) {
                return Collections.singletonList(version);
            }
        }

        // No specific version -> ask Artifactory for available versions (defensive)
        if (this.artifactoryClient == null) {
            return Collections.emptyList();
        }

        try {
            List<String> versions = this.artifactoryClient.getExistingVersion(org.toString(), name.toString());
            if (versions == null || versions.isEmpty()) {
                return Collections.emptyList();
            }
            return versions.stream()
                    .filter(Objects::nonNull)
                    .map(PackageVersion::from)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            final PrintStream out = System.out;
            out.println("Error while fetching package versions for [" + org + "/" + name + "]: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    protected DependencyGraph<PackageDescriptor> getDependencyGraph(PackageOrg org, PackageName name,
                                                                    PackageVersion version) {
        return this.fileSystemRepository.getDependencyGraph(org, name, version);
    }

    @Override
    public boolean isPackageExists(PackageOrg org, PackageName name, PackageVersion version) {
        if (version == null) {
            return false;
        }

        // Check local cache first
        if (this.fileSystemRepository.isPackageExists(org, name, version)) {
            return true;
        }

        // If not in cache, check if it exists in Artifactory
        if (this.artifactoryClient == null) {
            return false;
        }

        try {
            List<String> versions = this.artifactoryClient.getExistingVersion(org.toString(), name.toString());
            if (versions == null || versions.isEmpty()) {
                return false;
            }
            if(versions.contains(version.toString())){
                // If Artifactory reports the version exists, attempt to pull it to cache
                return getPackageFromRepository(org, name, version);

            }

            return false;
        } catch (IOException e) {
            final PrintStream out = System.out;
            out.println("Error while checking package existence [" + org + "/" + name + ":" + version + "]: " + e.getMessage());
            return false;
        }
    }

    @Override
    public Collection<ModuleDescriptor> getModules(PackageOrg org, PackageName name, PackageVersion version) {
        if (version == null) {
            return List.of();
        }
        boolean isPackageExists = isPackageExists(org, name, version);
        if(isPackageExists){
            return this.fileSystemRepository.getModules(org, name, version);
        }
        return List.of();
    }

    @Override
    public Optional<Package> getPackage(ResolutionRequest request, ResolutionOptions options) {
        Optional<Package> packageCache = this.fileSystemRepository.getPackage(request, options);
        if (packageCache.isPresent()) {
            return packageCache;
        }
        if(!options.offline() && request.version().isPresent()){
            PackageVersion v = request.version().get();
            getPackageFromRepository(request.orgName(), request.packageName(), v);
        }
        return this.fileSystemRepository.getPackage(request, options);
    }

    @Override
    public Collection<PackageVersion> getPackageVersions(ResolutionRequest request, ResolutionOptions options) {
        PackageOrg org = request.orgName();
        PackageName name = request.packageName();
        Optional<PackageVersion> versionOpt = request.version();

        if (versionOpt.isPresent()) {
            PackageVersion version = versionOpt.get();
            // Merge local cache + remote versions so compatibility filtering can select a newer compatible version
            Set<PackageVersion> packageVersions = new HashSet<>(this.fileSystemRepository.getPackageVersions(org, name, version));

            // If allowed, add remote versions
            if (!options.offline() && this.artifactoryClient != null) {
                try {
                    List<String> remotePackageVersions = this.artifactoryClient.getExistingVersion(org.toString(), name.toString());
                    remotePackageVersions.stream().map(PackageVersion::from).forEach(packageVersions::add);
                } catch (IOException e) {
                    // ignore and proceed with local versions only
                }
            }

            // Now apply compatibility rules using ProjectUtils
            SemanticVersion minSemVer = null;
            if (version != null) {
                minSemVer = SemanticVersion.from(version.toString());
            }

            List<SemanticVersion> semVers = packageVersions.stream()
                    .map(pkgVer -> SemanticVersion.from(pkgVer.toString())).toList();
            ProjectUtils.CompatibleRange compatibilityRange = ProjectUtils.getCompatibleRange(
                    minSemVer, options.packageLockingMode());
            List<SemanticVersion> compatibleVersions = ProjectUtils.getVersionsInCompatibleRange(
                    minSemVer, semVers, compatibilityRange);
            return compatibleVersions.stream().map(PackageVersion::from).collect(Collectors.toList());
        }

        // No specific version requested.
        if (options.offline()) {
            return Collections.emptyList();
        }

        // Ask remote for available versions
        if (this.artifactoryClient == null) {
            return Collections.emptyList();
        }

        try {
            List<String> versions = this.artifactoryClient.getExistingVersion(org.toString(), name.toString());
            if (versions == null || versions.isEmpty()) {
                return Collections.emptyList();
            }
            return versions.stream()
                    .filter(Objects::nonNull)
                    .map(PackageVersion::from)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            final PrintStream out = System.out;
            out.println("Error while fetching package versions for [" + org + "/" + name + "]: " + e.getMessage());
            return Collections.emptyList();
        }
    }


    @Override
    public Collection<PackageMetadataResponse> getPackageMetadata(Collection<ResolutionRequest> requests,
                                                                  ResolutionOptions options) {
        List<PackageMetadataResponse> descriptorSet = new ArrayList<>();
        for (ResolutionRequest request : requests) {
            Collection<PackageVersion> packageVersions = getPackageVersions(request, options);
            if (packageVersions.isEmpty()) {
                descriptorSet.add(PackageMetadataResponse.createUnresolvedResponse(request));
                continue;
            }
            PackageVersion latest = findLatest(new ArrayList<>(packageVersions));
            // Ensure the selected version is in local cache (triggers download from Artifactory if needed)
            isPackageExists(request.orgName(), request.packageName(), latest);
            DependencyGraph<PackageDescriptor> dependencyGraph = getDependencyGraph(
                    request.orgName(), request.packageName(), latest);
            PackageDescriptor resolvedDescriptor = PackageDescriptor.from(
                    request.orgName(), request.packageName(), latest,
                    request.repositoryName().orElse(null));
            descriptorSet.add(PackageMetadataResponse.from(request, resolvedDescriptor, dependencyGraph));
        }
        return descriptorSet;
    }

    @Override
    public Map<String, List<String>> getPackages() {
        return this.fileSystemRepository.getPackages();
    }

    public boolean getPackageFromRepository(PackageOrg org, PackageName name, PackageVersion version){
        if (org == null || name == null || version == null || this.artifactoryClient == null) {
            return false;
        }
        try {
            // Mirror Maven flow: download to temporary directory, extract bala, then copy to repo bala location
            Path tmpDownloadDirectory = Files.createTempDirectory("ballerina-" + System.nanoTime());
            // Ask Artifactory client to download the bala into the temporary download directory
            this.artifactoryClient.pullPackage(org.toString(), name.toString(), version.toString(), tmpDownloadDirectory.toString());

            Path balaDownloadPath = tmpDownloadDirectory.resolve(org.toString()).resolve(name.toString()).resolve(version.toString())
                    .resolve(name.toString() + "-" + version.toString() + BALA_EXTENSION);
            Path temporaryExtractionPath = tmpDownloadDirectory.resolve(org.toString()).resolve(name.toString()).resolve(version.toString()).resolve("platform");

            // Extract the bala tar/zip into temporaryExtractionPath
            ProjectUtils.extractBala(balaDownloadPath, temporaryExtractionPath);

            Path packageJsonPath = temporaryExtractionPath.resolve("package.json");
            try (BufferedReader bufferedReader = Files.newBufferedReader(packageJsonPath, StandardCharsets.UTF_8)) {
                JsonObject resultObj = new Gson().fromJson(bufferedReader, JsonObject.class);
                String platform = resultObj.get("platform").getAsString();
                Path actualBalaPath = this.balaDirectory.resolve(org.toString()).resolve(name.toString())
                        .resolve(version.toString()).resolve(platform);
                // Ensure parent directories exist
                Files.createDirectories(actualBalaPath);
                // Copy the extracted platform folder into the repo bala location
                // Use the FileUtils.Copy file visitor to copy the directory tree
                Files.walkFileTree(temporaryExtractionPath, new FileUtils.Copy(temporaryExtractionPath, actualBalaPath));
            }

            // Verify the package now exists at the expected location
            Path pkgPath = this.fileSystemRepository.getPackagePath(org.toString(), name.toString(), version.toString());
            if (Files.exists(pkgPath)) {
                System.out.println("DEBUG: Package exists at: " + pkgPath);
                return true;
            } else {
                System.out.println("DEBUG: After copy, package NOT found at expected path: " + pkgPath);
                return false;
            }
        } catch (IOException e) {
            final PrintStream out = System.out;
            out.println("ERROR while pulling package [" + org + "/" + name + ":" + version + "]: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
